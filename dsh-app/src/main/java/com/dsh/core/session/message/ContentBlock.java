package com.dsh.core.session.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 消息内容块, 对应 dsh 的 ContentBlock (packages/llm/llm/src/message.ts)。
 * 用 type 字段做 JSON 多态分派, 与事件信封同一套机制。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = TextBlock.class, name = TextBlock.TYPE_TEXT),
        @JsonSubTypes.Type(value = ToolCallBlock.class, name = ToolCallBlock.TYPE_TOOL_CALL),
        @JsonSubTypes.Type(value = ToolResultBlock.class, name = ToolResultBlock.TYPE_TOOL_RESULT),
})
@JsonIgnoreProperties(ignoreUnknown = true)
public sealed interface ContentBlock permits TextBlock, ToolCallBlock, ToolResultBlock {

    String type();
}
