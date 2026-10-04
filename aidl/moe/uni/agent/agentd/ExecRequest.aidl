package moe.uni.agent.agentd;

parcelable ExecRequest {
    /** 要执行的程序和参数；shell 由调用方包装（ADR-0011）。 */
    String[] argv;
    /** 工作目录；为空时使用 HOME，即 IAgentd.HOME_DIR（ADR-0029）。 */
    @nullable String cwd;
    /** 大于 0 为超时毫秒数；0 表示 exec 默认 10 分钟、task 不限时；-1 表示不限时。 */
    long timeoutMs;
    /** true 时 ExecHandle.stdin 返回写端；否则 stdin 接 /dev/null。task 忽略此项。 */
    boolean pipeStdin;
}
