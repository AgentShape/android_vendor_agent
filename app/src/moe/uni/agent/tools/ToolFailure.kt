package moe.uni.agent.tools

/**
 * 工具无法完成，作为工具结果的 error 交给模型（不显示给用户）。code 是 ToolError 的取值，
 * message 只陈述事实，不带补救建议（ADR-0030 第 2 条）。
 */
class ToolFailure(val code: String, message: String) : Exception(message)
