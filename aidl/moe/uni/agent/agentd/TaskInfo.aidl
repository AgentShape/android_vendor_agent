package moe.uni.agent.agentd;

import moe.uni.agent.agentd.ExecStatus;

parcelable TaskInfo {
    int id;
    /** 启动它的 session 的 tag。 */
    String tag;
    /** argv 的摘要。 */
    String summary;
    long startTimeMs;
    /** 组长的状态。 */
    ExecStatus status;
    /** 进程组里是否还有进程（组长退出后后台进程可能仍在运行）。 */
    boolean alive;
}
