package moe.uni.agent.agentd;

import moe.uni.agent.agentd.ExecHandle;
import moe.uni.agent.agentd.ExecRequest;
import moe.uni.agent.agentd.ExecStatus;

/** 一次对话的能力调用入口（ADR-0020）。错误码约定见 IAgentd。 */
interface IAgentdSession {
    /**
     * 拉起前台命令，立即返回输出管道。读到 EOF 表示命令已结束且状态已记录，
     * 此时调用 status() 一定得到最终状态；残留的后台进程已被整组 kill。
     */
    ExecHandle exec(in ExecRequest request);

    /** 非阻塞。 */
    ExecStatus status(int execId);

    /** 向 exec 的整个进程组发信号。 */
    void signal(int execId, int signal);

    /** 启动 task，返回 task id。task 记在本 session 名下，但不随 session 结束。 */
    int startTask(in ExecRequest request);

    /** 结束 session：整组 kill 仍在运行的 exec。 */
    void close();
}
