//! 为 AgentFramework 创建虚拟屏幕需要的 CDM 关联（ADR-0022 第 3 条、ADR-0029 第 7 条）。
//!
//! `cmd companiondevice associate` 要求 shell 或 root 身份，AgentFramework（system uid）自己执行不了。
//! 命令写死，只接受 user id，经 trampoline 在 `agentd_child` 中执行。

use std::io::{self, Read};
use std::path::Path;

use super::spawn;

/// AgentFramework 的包名，与 IAgentScreen.PACKAGE_NAME 一致（这里引用不到 framework 的 AIDL，改动时同步）。
const PACKAGE: &str = "moe.uni.agent.framework";
/// 关联标识。本构建 shell 旧格式要求 MAC 形式（ADR-0022 第 3 条）。
const MAC: &str = "02:00:00:00:50:03";
const PROFILE: &str = "android.app.role.COMPANION_DEVICE_APP_STREAMING";
const SH: &str = "/system/bin/sh";
const CWD: &str = "/";

/// 创建关联并列出当前关联，返回命令的全部输出。
pub fn create(user: i32) -> io::Result<String> {
    let script = format!(
        "cmd companiondevice associate {user} {PACKAGE} {MAC} {PROFILE} true; cmd companiondevice list {user}"
    );
    let argv = [SH.to_string(), "-c".to_string(), script];
    let mut spawned = spawn::spawn(&argv, Path::new(CWD), false, false)?;
    let mut output = Vec::new();
    spawned.output.read_to_end(&mut output)?;
    spawned.child.wait()?;
    Ok(String::from_utf8_lossy(&output).into_owned())
}
