package com.dsh.core.session.message;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 工具结果内容块, 出现在 role=user 的 ToolResultMessage 里 (provider 视角工具结果就是 user 消息)。
 * isError=true 时 content 是错误说明。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ToolResultBlock(String type, String callId, List<ContentBlock> content, boolean isError)
        implements ContentBlock {

    public static final String TYPE_TOOL_RESULT = "tool_result";

    public static ToolResultBlock of(String callId, List<ContentBlock> content, boolean isError) {
        return new ToolResultBlock(TYPE_TOOL_RESULT, callId, content, isError);
    }

    public ToolResultBlock {
        if (type == null) {
            type = TYPE_TOOL_RESULT;
        }
        content = content == null ? List.of() : List.copyOf(content);
    }
}
