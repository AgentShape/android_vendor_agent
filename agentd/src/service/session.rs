//! 一次对话的能力调用入口（ADR-0020）。
//!
//! session 是交给调用方的 binder 对象：调用方 close()，或者它所在的进程死亡、对象被释放
//! （Drop）时，整组 kill 仍在运行的 exec。agentd 不回调调用方。

use std::collections::BTreeMap;
use std::os::fd::OwnedFd;
use std::path::Path;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use agentd_aidl_interface::aidl::moe::uni::agent::agentd::{
    ExecHandle::ExecHandle, ExecRequest::ExecRequest, ExecStatus::ExecStatus, IAgentd::*, IAgentdSession::IAgentdSession,
};
use binder::{Interface, ParcelFileDescriptor};

use crate::exec::process::Process;
use crate::exec::spawn;
use crate::exec::task::Tasks;
use crate::service::{dispatch, error, io_error, request_cwd};

const DEFAULT_EXEC_TIMEOUT: Duration = Duration::from_secs(10 * 60);

pub struct Session {
    tag: String,
    tasks: Arc<Tasks>,
    execs: Mutex<Execs>,
}

#[derive(Default)]
struct Execs {
    next: i32,
    map: BTreeMap<i32, Arc<Process>>,
    closed: bool,
}

impl Session {
    pub fn new(tag: String, tasks: Arc<Tasks>) -> Self {
        Self { tag, tasks, execs: Mutex::new(Execs { next: 1, ..Default::default() }) }
    }

    fn exec_impl(&self, req: &ExecRequest) -> binder::Result<ExecHandle> {
        if req.argv.is_empty() || req.timeoutMs < -1 {
            return Err(error(ERROR_INVALID_ARGUMENT, "empty argv or timeoutMs < -1"));
        }
        let timeout = match req.timeoutMs {
            -1 => None,
            0 => Some(DEFAULT_EXEC_TIMEOUT),
            ms => Some(Duration::from_millis(ms as u64)),
        };
        let mut execs = self.execs.lock().unwrap();
        if execs.closed {
            return Err(error(ERROR_INVALID_ARGUMENT, "session closed"));
        }
        let mut spawned = spawn::spawn(&req.argv, Path::new(request_cwd(req)), req.pipeStdin, true)
            .map_err(io_error)?;
        let stdin = spawned.child.stdin.take().map(|s| ParcelFileDescriptor::new(OwnedFd::from(s)));
        let held_writer = spawned.held_writer.take();
        let pgid = spawned.pgid;
        // exec 只负责前台命令：状态记录后整组 kill 残留的后台进程，再关闭保留的写端，
        // 调用方读到 EOF 时状态一定已经可查。
        let process = Process::watch(spawned.child, pgid, timeout, move || {
            spawn::kill_group(pgid);
            drop(held_writer);
        });
        let id = execs.next;
        execs.next += 1;
        execs.map.insert(id, process);
        Ok(ExecHandle { id, output: Some(ParcelFileDescriptor::new(OwnedFd::from(spawned.output))), stdin })
    }

    fn process(&self, id: i32) -> binder::Result<Arc<Process>> {
        self.execs.lock().unwrap().map.get(&id).cloned()
            .ok_or_else(|| error(ERROR_NO_SUCH_ID, "no such exec"))
    }

    fn close_impl(&self) {
        let mut execs = self.execs.lock().unwrap();
        execs.closed = true;
        // 已结束的 exec 在组长退出时就清理过了；它们的 pgid 可能被复用，不能再发信号。
        for process in execs.map.values().filter(|p| p.running()) {
            spawn::kill_group(process.pgid);
        }
    }
}

impl Drop for Session {
    fn drop(&mut self) {
        self.close_impl();
    }
}

impl Interface for Session {}

impl IAgentdSession for Session {
    fn exec(&self, req: &ExecRequest) -> binder::Result<ExecHandle> {
        dispatch(&self.tag, "exec", || self.exec_impl(req))
    }
    fn status(&self, id: i32) -> binder::Result<ExecStatus> {
        dispatch(&self.tag, "status", || Ok(self.process(id)?.status()))
    }
    fn signal(&self, id: i32, signal: i32) -> binder::Result<()> {
        dispatch(&self.tag, "signal", || {
            let process = self.process(id)?;
            if !process.running() {
                return Err(error(libc::ESRCH, "exec already finished"));
            }
            spawn::signal_group(process.pgid, signal).map_err(io_error)
        })
    }
    fn startTask(&self, req: &ExecRequest) -> binder::Result<i32> {
        dispatch(&self.tag, "startTask", || {
            if req.argv.is_empty() || req.timeoutMs < -1 {
                return Err(error(ERROR_INVALID_ARGUMENT, "empty argv or timeoutMs < -1"));
            }
            self.tasks.start(&self.tag, req)
        })
    }
    fn close(&self) -> binder::Result<()> {
        dispatch(&self.tag, "close", || {
            self.close_impl();
            Ok(())
        })
    }
}
