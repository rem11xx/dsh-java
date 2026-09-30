package com.dsh.core.session.message;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 一条消息 = 角色 + 内容块列表。既作为事件的内嵌消息 (system/assistant/tool-result),
 * 也是 deriveMessages() 的投影产物, 一个类型贯穿, 对应 dsh 的 Message。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Message(Role role, List<ContentBlock> content) {

    public Message {
        content = content == null ? List.of() : List.copyOf(content);
    }

    public static Message of(Role role, String text) {
        return new Message(role, List.of(TextBlock.of(text)));
    }
}
