//! IAgentd 的实现，以及所有入口共用的 dispatch（ADR-0002、ADR-0010、ADR-0020）。

pub mod session;

use std::ffi::CStr;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use agentd_aidl_interface::aidl::moe::uni::agent::agentd::{
    ExecRequest::ExecRequest,
    IAgentd::{IAgentd, ERROR_INVALID_ARGUMENT, ERROR_PROTOCOL, ERROR_TOO_LARGE, MAX_CHUNK_BYTES},
    IAgentdSession::{BnAgentdSession, IAgentdSession},
    TaskInfo::TaskInfo,
    TaskOutput::TaskOutput,
};
use binder::{BinderFeatures, Interface, Status, Strong, ThreadState};
use log::{info, warn};

use crate::exec::association;
use crate::exec::task::Tasks;
use session::Session;

const VERSION: &str = "0.2";

/// 允许调用 agentd 的 SELinux 域（ADR-0010）：AgentApp 与 AgentFramework，两者本是一体（ADR-0029 第 7 条）。
const ALLOWED_CALLER_DOMAINS: [&str; 2] = ["agent_app", "agent_framework"];

pub struct Agentd {
    pub tasks: Arc<Tasks>,
}

impl Interface for Agentd {}

impl IAgentd for Agentd {
    fn ping(&self) -> binder::Result<String> {
        dispatch("", "ping", || Ok(format!("agentd {VERSION}")))
    }
    fn openSession(&self, tag: &str) -> binder::Result<Strong<dyn IAgentdSession>> {
        dispatch(tag, "openSession", || {
            Ok(BnAgentdSession::new_binder(Session::new(tag.to_string(), self.tasks.clone()), features()))
        })
    }
    fn listTasks(&self) -> binder::Result<Vec<TaskInfo>> {
        dispatch("", "listTasks", || Ok(self.tasks.list()))
    }
    fn readTask(&self, id: i32, cursor: i64, max_bytes: i32) -> binder::Result<TaskOutput> {
        dispatch("", "readTask", || {
            if !(1..=MAX_CHUNK_BYTES).contains(&max_bytes) {
                return Err(error(ERROR_TOO_LARGE, "maxBytes must be 1..MAX_CHUNK_BYTES"));
            }
            self.tasks.read(id, cursor, max_bytes as usize)
        })
    }
    fn signalTask(&self, id: i32, signal: i32) -> binder::Result<()> {
        dispatch("", "signalTask", || self.tasks.signal(id, signal))
    }
    fn stopTask(&self, id: i32) -> binder::Result<()> {
        dispatch("", "stopTask", || self.tasks.stop(id))
    }
    fn createScreenAssociation(&self, user_id: i32) -> binder::Result<String> {
        dispatch("", "createScreenAssociation", || {
            if user_id < 0 {
                return Err(error(ERROR_INVALID_ARGUMENT, "userId must be >= 0"));
            }
            association::create(user_id).map_err(io_error)
        })
    }
}

/// 打开 requesting-sid，每次调用才能拿到调用方的 SELinux 上下文（ADR-0010）。
pub fn features() -> BinderFeatures {
    BinderFeatures { set_requesting_sid: true, ..BinderFeatures::default() }
}

/// 所有入口都经过这里（ADR-0002）：先鉴权，再执行，最后记一行摘要日志（不记录内容）。
/// tag 是调用所属 session 的对话标识；以后的审批插件（ADR-0017）挂在鉴权之后、执行之前。
pub fn dispatch<T>(tag: &str, method: &str, run: impl FnOnce() -> binder::Result<T>) -> binder::Result<T> {
    let uid = ThreadState::get_calling_uid();
    let allowed = ThreadState::with_calling_sid(|sid| sid.is_some_and(is_allowed_caller));
    if !allowed {
        warn!("{method} denied: uid={uid}");
        return Err(Status::new_exception_str(
            binder::ExceptionCode::SECURITY,
            Some("caller is not agent_app or agent_framework"),
        ));
    }
    let result = run();
    match &result {
        Ok(_) => info!("{method} ok: tag={tag} uid={uid}"),
        Err(status) => info!("{method} failed: tag={tag} uid={uid} status={status}"),
    }
    result
}

/// 调用方上下文形如 `u:r:agent_app:s0:c512,c768`，只比较第三段（域）。
fn is_allowed_caller(sid: &CStr) -> bool {
    sid.to_str().ok().and_then(|s| s.split(':').nth(2)).is_some_and(|domain| ALLOWED_CALLER_DOMAINS.contains(&domain))
}

pub fn error(code: i32, message: &str) -> Status {
    Status::new_service_specific_error_str(code, Some(message))
}

/// 系统调用错误直接用 errno 作错误码（ADR-0020）。
pub fn io_error(e: std::io::Error) -> Status {
    error(e.raw_os_error().unwrap_or(ERROR_PROTOCOL), &e.to_string())
}

/// 没有指定 cwd 时用 HOME（ADR-0021）。
pub fn request_cwd(req: &ExecRequest) -> &str {
    req.cwd.as_deref().filter(|cwd| !cwd.is_empty()).unwrap_or(crate::exec::runtime::HOME)
}

pub fn summary(argv: &[String]) -> String {
    argv.join(" ").chars().take(256).collect()
}

pub fn now_ms() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as i64
}
