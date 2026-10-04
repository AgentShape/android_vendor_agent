package moe.uni.agent.agentd;

parcelable ExecStatus {
    const int RUNNING = 0;
    const int EXITED = 1;
    const int KILLED = 2;
    const int TIMED_OUT = 3;

    int state;
    /** state 为 EXITED 时有效。 */
    int exitCode;
    /** state 为 KILLED 或 TIMED_OUT 时有效。 */
    int signal;
}
