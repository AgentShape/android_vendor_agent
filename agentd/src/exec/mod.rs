//! 子进程：拉起与域切换（spawn）、进程组生命周期（process）、执行环境（runtime）、常驻 task（task），
//! 以及写死用途的命令（association）。

pub mod association;
pub mod process;
pub mod runtime;
pub mod spawn;
pub mod task;
