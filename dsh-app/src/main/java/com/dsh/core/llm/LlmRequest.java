package com.dsh.core.llm;

import com.dsh.core.session.message.Message;

import java.util.List;

/**
 * 一次模型请求, 对应 dsh 的 GenerateOptions 中与 provider 相关的最小子集。
 * messages 直接复用 session 的 Message 类型: 模型可见即已记录, 请求内容必须能从日志重建。
 * tools 为可选的 JSON Schema 描述列表, D4 工具系统会填充。
 */
public record LlmRequest(List<Message> messages, List<ToolSchema> tools) {

    public LlmRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public static LlmRequest of(List<Message> messages) {
        return new LlmRequest(messages, List.of());
    }
}
