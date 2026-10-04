//! agentd：agent 的执行层守护进程（ADR-0001、ADR-0004）。
//!
//! 写法约束：同步代码加线程，不使用 async；避免复杂的泛型和宏。
//!
//! 同一个二进制有两种运行方式：
//! - 不带参数：由 init 拉起的守护进程，运行在 `agentd` 域；
//! - `--exec <cwd> <argv...>`：子进程 trampoline。守护进程重新执行自己，经
//!   `agentd_exec → agentd_child` 切换域后，再执行真正的程序（ADR-0009）。

mod exec;
mod service;

use std::os::unix::process::CommandExt;
use std::process::Command;
use std::sync::Arc;

use binder::ProcessState;
use log::{error, info};

use agentd_aidl_interface::aidl::moe::uni::agent::agentd::IAgentd::{BnAgentd, SERVICE_NAME};

use exec::{runtime, spawn, task};

const BINDER_THREADS: u32 = 4;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() >= 2 && args[1] == spawn::EXEC_FLAG {
        run_exec_trampoline(&args[2..]);
    }
    run_daemon();
}

/// 已经在 `agentd_child` 域里：设置执行环境（ADR-0021）后直接替换成目标程序，
/// 失败时以 127 退出（与 shell 一致）。
fn run_exec_trampoline(argv: &[String]) -> ! {
    if argv.len() < 2 {
        eprintln!("agentd --exec: missing cwd or command");
        std::process::exit(127);
    }
    runtime::ensure_home();
    let mut command = Command::new(&argv[1]);
    command.args(&argv[2..]).current_dir(&argv[0]);
    runtime::apply_env(&mut command);
    let err = command.exec();
    eprintln!("agentd --exec: {}: {err}", argv[1]);
    std::process::exit(127);
}

fn run_daemon() {
    android_logger::init_once(
        android_logger::Config::default()
            .with_tag("agentd")
            .with_max_level(log::LevelFilter::Info),
    );

    ProcessState::set_thread_pool_max_thread_count(BINDER_THREADS);
    ProcessState::start_thread_pool();

    let agentd = service::Agentd { tasks: Arc::new(task::Tasks::new()) };
    let binder = BnAgentd::new_binder(agentd, service::features());
    if let Err(e) = binder::add_service(SERVICE_NAME, binder.as_binder()) {
        error!("failed to register {SERVICE_NAME}: {e:?}");
        std::process::exit(1);
    }
    info!("{SERVICE_NAME} started");

    ProcessState::join_thread_pool();
}
