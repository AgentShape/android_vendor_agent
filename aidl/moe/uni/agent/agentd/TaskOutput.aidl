package moe.uni.agent.agentd;

parcelable TaskOutput {
    byte[] data;
    long nextCursor;
    /** 请求的起点已被环形缓冲覆盖时，跳过的字节数。 */
    long dropped;
    /** 输出已到 EOF 且已全部读完。 */
    boolean closed;
}
