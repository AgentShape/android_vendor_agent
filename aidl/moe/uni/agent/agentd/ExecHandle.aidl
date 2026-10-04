package moe.uni.agent.agentd;

parcelable ExecHandle {
    int id;
    /** stdout 与 stderr 合并后的读端。 */
    ParcelFileDescriptor output;
    /** ExecRequest.pipeStdin 为 true 时是写端，否则为 null。 */
    @nullable ParcelFileDescriptor stdin;
}
