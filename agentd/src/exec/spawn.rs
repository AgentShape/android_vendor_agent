//! 拉起子进程的原语（ADR-0009、ADR-0020）。
//!
//! 子进程一律经 `agentd --exec` 重新执行自身，切换到 `agentd_child` 域，
//! 并放进独立的进程组，便于整组发信号。
//!
//! 不使用 PR_SET_PDEATHSIG：它在 fork 所在线程退出时就会触发，与 binder 线程池不兼容。
//! agentd 停止或崩溃时，init 会按 cgroup 清理全部子进程（ADR-0012 阶段 2 实测）。

use std::io::{self, PipeReader, PipeWriter};
use std::os::unix::process::CommandExt;
use std::path::Path;
use std::process::{Child, Command, Stdio};

pub const EXEC_FLAG: &str = "--exec";

const AGENTD_PATH: &str = "/system_ext/bin/agentd";

pub struct Spawned {
    pub child: Child,
    /// 进程组 id，等于子进程的 pid。
    pub pgid: i32,
    /// 子进程 stdout 的读端；exec 和 task 还合并了 stderr。
    pub output: PipeReader,
    /// exec 由 agentd 自己保留的一份写端，记录完状态后才关闭，保证读端 EOF 晚于状态发布。
    pub held_writer: Option<PipeWriter>,
}

/// exec 与 task 的子进程：stdout/stderr 合并到同一个管道。
pub fn spawn(argv: &[String], cwd: &Path, pipe_stdin: bool, hold_writer: bool) -> io::Result<Spawned> {
    let mut command = Command::new(AGENTD_PATH);
    command.arg(EXEC_FLAG).arg(cwd).args(argv);
    let (reader, writer) = io::pipe()?;
    command.stderr(writer.try_clone()?);
    let held_writer = if hold_writer { Some(writer.try_clone()?) } else { None };
    command.stdout(writer);
    command.stdin(if pipe_stdin { Stdio::piped() } else { Stdio::null() });
    start(command, reader, held_writer)
}

fn start(mut command: Command, output: PipeReader, held_writer: Option<PipeWriter>) -> io::Result<Spawned> {
    // std 创建的管道带 O_CLOEXEC，不会泄漏给其他线程同时拉起的子进程。
    let child = command.process_group(0).spawn()?;
    // command 里还握着管道写端；必须释放，否则读端永远等不到 EOF。
    drop(command);
    let pgid = child.id() as i32;
    Ok(Spawned { child, pgid, output, held_writer })
}

/// 向整个进程组发信号。agentd 域只被允许向 agentd_child 发信号（ADR-0009）。
pub fn signal_group(pgid: i32, signal: i32) -> io::Result<()> {
    // SAFETY: kill 只接受整数参数，没有内存安全方面的前提。
    if unsafe { libc::kill(-pgid, signal) } == 0 {
        Ok(())
    } else {
        Err(io::Error::last_os_error())
    }
}

/// 整组 SIGKILL；进程组已经不存在时视为成功。
pub fn kill_group(pgid: i32) {
    let _ = signal_group(pgid, libc::SIGKILL);
}

pub fn group_alive(pgid: i32) -> bool {
    match signal_group(pgid, 0) {
        Ok(()) => true,
        Err(e) => e.raw_os_error() != Some(libc::ESRCH),
    }
}
