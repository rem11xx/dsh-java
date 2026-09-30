package com.dsh.core.llm;

import com.dsh.core.session.message.Message;

/**
 * 一次模型调用的响应。message 是 assistant 消息 (text/tool_call 内容块),
 * usage 供 token 统计, finishReason 告诉 AgentLoop 是否还有工具调用要执行。
 * v1 非流式, 无 dsh 的 stream 词汇表。
 */
public record LlmResponse(Message message, FinishReason finishReason, TokenUsage usage) {
}
