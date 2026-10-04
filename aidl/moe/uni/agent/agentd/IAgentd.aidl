package moe.uni.agent.agentd;

import moe.uni.agent.agentd.IAgentdSession;
import moe.uni.agent.agentd.TaskInfo;
import moe.uni.agent.agentd.TaskOutput;

/**
 * agentd 的入口（ADR-0020）。只允许 SELinux 域为 agent_app、agent_framework 的调用方使用（ADR-0010、ADR-0029 第 7 条）。
 * agentd 不回调调用方，状态一律由调用方查询。
 *
 * 错误通过 ServiceSpecificException 返回：系统调用错误直接用 errno（1～4095）作错误码，
 * 本协议自己的错误用下面的 ERROR_* 常量。
 */
interface IAgentd {
    /** servicemanager 中的服务名。 */
    const String SERVICE_NAME = "agentd";

    /**
     * exec 与 task 的 HOME 和 TMPDIR（ADR-0029 第 2、4 条）。HOME 是 agent 在用户存储中的目录，
     * 由 AgentApp 创建，不存在时 trampoline 补建；TMPDIR 放可执行文件和 system_server 要读的文件。
     */
    const String HOME_DIR = "/sdcard/Agent";
    const String TMP_DIR = "/data/local/tmp";

    const int ERROR_INVALID_ARGUMENT = 10001;
    const int ERROR_TOO_LARGE = 10002;
    const int ERROR_NO_SUCH_ID = 10004;
    const int ERROR_PROTOCOL = 10005;

    /** 单次调用传输数据的上限（ADR-0007），现在用于 readTask。 */
    const int MAX_CHUNK_BYTES = 262144;

    String ping();

    /**
     * 为一次对话打开 session。tag 由调用方给出（对话标识），只用于日志和审批。
     * session 被 close() 或随调用方进程释放时，其下仍在运行的 exec 会被整组 kill。
     */
    IAgentdSession openSession(String tag);

    /** task 不随 session 结束，由下面的方法管理（ADR-0016、ADR-0020）。 */
    TaskInfo[] listTasks();

    /** cursor 是 task 输出流的绝对字节偏移；超出环形缓冲的部分计入 dropped。 */
    TaskOutput readTask(int taskId, long cursor, int maxBytes);

    /** 向 task 的整个进程组发信号。 */
    void signalTask(int taskId, int signal);

    /** 整组 kill 并移除 task。 */
    void stopTask(int taskId);

    /**
     * 为 AgentFramework 创建虚拟屏幕需要的 APP_STREAMING 关联（ADR-0022 第 3 条、ADR-0029 第 7 条）。命令写死，
     * 只接受 user id；返回命令的输出。关联的角色授予是异步的，调用方要自己确认关联已经生效。
     */
    String createScreenAssociation(int userId);
}
