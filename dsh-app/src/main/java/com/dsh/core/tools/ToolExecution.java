package com.dsh.core.tools;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 一次待执行的工具调用, 对应 dsh 的 ToolExecution (去掉 token/signal 等进程内细节)。
 * callId 是模型在 ToolCallBlock 里产出的调用 id, 结果与调用靠它配对。
 */
public record ToolExecution(String callId, String name, JsonNode args) {

    public ToolExecution {
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("callId 不能为空");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("工具名不能为空");
        }
        args = args == null ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode() : args;
    }
}
