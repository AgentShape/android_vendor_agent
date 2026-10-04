//! task：不随 session 结束的常驻进程组，输出放进内存环形缓冲（ADR-0016、ADR-0020）。

use std::collections::{BTreeMap, VecDeque};
use std::io::Read;
use std::path::Path;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

use agentd_aidl_interface::aidl::moe::uni::agent::agentd::{
    ExecRequest::ExecRequest, IAgentd::ERROR_NO_SUCH_ID, TaskInfo::TaskInfo, TaskOutput::TaskOutput,
};

use crate::exec::process::Process;
use crate::exec::spawn;
use crate::service::{error, io_error, request_cwd, summary, now_ms};

const RING_BYTES: usize = 256 * 1024;

pub struct Tasks {
    inner: Mutex<(i32, BTreeMap<i32, Arc<Task>>)>,
}

struct Task {
    tag: String,
    summary: String,
    start_ms: i64,
    process: Arc<Process>,
    output: Mutex<Ring>,
}

#[derive(Default)]
struct Ring {
    buf: VecDeque<u8>,
    /// 到目前为止产生的总字节数，也就是下一个字节的游标。
    total: i64,
    eof: bool,
}

impl Tasks {
    pub fn new() -> Self {
        Self { inner: Mutex::new((1, BTreeMap::new())) }
    }

    pub fn start(&self, tag: &str, req: &ExecRequest) -> binder::Result<i32> {
        // 0 和 -1 都表示不设超时。
        let timeout = (req.timeoutMs > 0).then(|| Duration::from_millis(req.timeoutMs as u64));
        let spawned = spawn::spawn(&req.argv, Path::new(request_cwd(req)), false, false)
            .map_err(io_error)?;
        let process = Process::watch(spawned.child, spawned.pgid, timeout, || {});
        let task = Arc::new(Task {
            tag: tag.to_string(),
            summary: summary(&req.argv),
            start_ms: now_ms(),
            process,
            output: Mutex::new(Ring::default()),
        });

        let reader = task.clone();
        let mut output = spawned.output;
        thread::spawn(move || {
            let mut buf = [0u8; 8192];
            loop {
                let n = output.read(&mut buf).unwrap_or(0);
                let mut ring = reader.output.lock().unwrap();
                if n == 0 {
                    ring.eof = true;
                    return;
                }
                ring.buf.extend(&buf[..n]);
                ring.total += n as i64;
                let excess = ring.buf.len().saturating_sub(RING_BYTES);
                ring.buf.drain(..excess);
            }
        });

        let mut inner = self.inner.lock().unwrap();
        let id = inner.0;
        inner.0 += 1;
        inner.1.insert(id, task);
        Ok(id)
    }

    pub fn list(&self) -> Vec<TaskInfo> {
        let inner = self.inner.lock().unwrap();
        inner.1.iter().map(|(id, task)| TaskInfo {
            id: *id,
            tag: task.tag.clone(),
            summary: task.summary.clone(),
            startTimeMs: task.start_ms,
            status: task.process.status(),
            alive: spawn::group_alive(task.process.pgid),
        }).collect()
    }

    pub fn read(&self, id: i32, cursor: i64, max_bytes: usize) -> binder::Result<TaskOutput> {
        let task = self.get(id)?;
        let ring = task.output.lock().unwrap();
        let first = ring.total - ring.buf.len() as i64;
        let from = cursor.clamp(first, ring.total);
        let skip = (from - first) as usize;
        let data: Vec<u8> = ring.buf.iter().skip(skip).take(max_bytes).copied().collect();
        let next = from + data.len() as i64;
        Ok(TaskOutput {
            dropped: (first - cursor).max(0),
            closed: ring.eof && next == ring.total,
            nextCursor: next,
            data,
        })
    }

    pub fn signal(&self, id: i32, signal: i32) -> binder::Result<()> {
        spawn::signal_group(self.get(id)?.process.pgid, signal).map_err(io_error)
    }

    pub fn stop(&self, id: i32) -> binder::Result<()> {
        let task = self.inner.lock().unwrap().1.remove(&id)
            .ok_or_else(|| error(ERROR_NO_SUCH_ID, "no such task"))?;
        // 进程组已不存在时，pgid 可能被复用，不再发信号。
        if spawn::group_alive(task.process.pgid) {
            spawn::kill_group(task.process.pgid);
        }
        Ok(())
    }

    fn get(&self, id: i32) -> binder::Result<Arc<Task>> {
        self.inner.lock().unwrap().1.get(&id).cloned()
            .ok_or_else(|| error(ERROR_NO_SUCH_ID, "no such task"))
    }
}
