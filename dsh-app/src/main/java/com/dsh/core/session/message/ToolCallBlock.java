package com.dsh.core.session.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 工具调用内容块, 出现在 assistant 消息里。
 * arguments 保留模型产出的原始 JSON 字符串, 不解析 (解析失败也要留痕, 对应 dsh 的讲究点)。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ToolCallBlock(String type, String callId, String name, String arguments) implements ContentBlock {

    public static final String TYPE_TOOL_CALL = "tool_call";

    public static ToolCallBlock of(String callId, String name, String arguments) {
        return new ToolCallBlock(TYPE_TOOL_CALL, callId, name, arguments);
    }

    public ToolCallBlock {
        if (type == null) {
            type = TYPE_TOOL_CALL;
        }
    }
}
