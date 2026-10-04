//! exec/task 子进程的执行环境（ADR-0021）。
//!
//! 只能在 trampoline 里、切换到 `agentd_child` 之后设置：域切换带 AT_SECURE，
//! bionic 会删掉 `TMPDIR` 等变量，写在 agentd.rc 里传不到子进程。

use std::os::unix::fs::PermissionsExt;
use std::process::Command;

/// HOME 在用户存储中（ADR-0029），TMPDIR 是 `/data/local/tmp`；路径以 AIDL 常量为唯一来源（ADR-0025）。
pub use agentd_aidl_interface::aidl::moe::uni::agent::agentd::IAgentd::{HOME_DIR as HOME, TMP_DIR as TMPDIR};

/// 与 app 包装 shell 的规则一致（ADR-0011）：bash 可执行时用 bash，否则用 sh。
const BASH: &str = "/system_ext/bin/bash";
const SH: &str = "/system/bin/sh";

/// HOME 由 AgentApp 创建；被删掉时补建。失败时不处理，由随后的 chdir 报错。TMPDIR 是系统目录，不需要创建。
pub fn ensure_home() {
    let _ = std::fs::create_dir_all(HOME);
}

/// 在 init 给的环境之上覆盖设置固定的几项。
pub fn apply_env(command: &mut Command) {
    command
        .env("HOME", HOME)
        .env("TMPDIR", TMPDIR)
        .env("USER", "root")
        .env("SHELL", shell())
        .env("LANG", "C.UTF-8")
        .env("TERM", "dumb")
        .env("PAGER", "cat");
    if let Ok(Some(host)) = rustutils::android::system_properties::read("ro.product.device") {
        if !host.is_empty() {
            command.env("HOSTNAME", host);
        }
    }
}

fn shell() -> &'static str {
    let executable = std::fs::metadata(BASH).is_ok_and(|m| m.is_file() && m.permissions().mode() & 0o111 != 0);
    if executable { BASH } else { SH }
}
