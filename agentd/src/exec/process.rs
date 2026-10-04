//! exec 与 task 共用的进程状态：等待组长退出、记录状态、处理超时（ADR-0020）。

use std::process::Child;
use std::os::unix::process::ExitStatusExt;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::thread;
use std::time::Duration;

use agentd_aidl_interface::aidl::moe::uni::agent::agentd::ExecStatus::{
    ExecStatus, EXITED, KILLED, RUNNING, TIMED_OUT,
};
use log::error;

use crate::exec::spawn;

pub struct Process {
    pub pgid: i32,
    status: Mutex<ExecStatus>,
    finished: Condvar,
    timed_out: AtomicBool,
}

impl Process {
    /// 启动等待线程；timeout 不为 None 时再启动超时线程。
    /// on_exit 在状态记录之后调用（exec 在这里清理后台进程并关闭保留的写端）。
    pub fn watch(
        mut child: Child,
        pgid: i32,
        timeout: Option<Duration>,
        on_exit: impl FnOnce() + Send + 'static,
    ) -> Arc<Self> {
        let process = Arc::new(Self {
            pgid,
            status: Mutex::new(ExecStatus { state: RUNNING, exitCode: 0, signal: 0 }),
            finished: Condvar::new(),
            timed_out: AtomicBool::new(false),
        });

        let waiter = process.clone();
        thread::spawn(move || {
            let result = child.wait();
            let mut status = waiter.status.lock().unwrap();
            match result {
                Ok(exit) => {
                    status.exitCode = exit.code().unwrap_or(0);
                    status.signal = exit.signal().unwrap_or(0);
                    status.state = if waiter.timed_out.load(Ordering::SeqCst) {
                        TIMED_OUT
                    } else if exit.code().is_some() {
                        EXITED
                    } else {
                        KILLED
                    };
                }
                Err(e) => {
                    error!("wait pgid {pgid}: {e}");
                    status.state = KILLED;
                }
            }
            waiter.finished.notify_all();
            drop(status);
            on_exit();
        });

        if let Some(timeout) = timeout {
            let timer = process.clone();
            thread::spawn(move || {
                let status = timer.status.lock().unwrap();
                let (status, _) = timer
                    .finished
                    .wait_timeout_while(status, timeout, |s| s.state == RUNNING)
                    .unwrap();
                if status.state == RUNNING {
                    timer.timed_out.store(true, Ordering::SeqCst);
                    spawn::kill_group(timer.pgid);
                }
            });
        }
        process
    }

    pub fn status(&self) -> ExecStatus {
        let status = self.status.lock().unwrap();
        ExecStatus { state: status.state, exitCode: status.exitCode, signal: status.signal }
    }

    pub fn running(&self) -> bool {
        self.status.lock().unwrap().state == RUNNING
    }
}
