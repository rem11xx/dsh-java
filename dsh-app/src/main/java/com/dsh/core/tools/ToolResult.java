package com.dsh.core.tools;

/**
 * 工具执行结果, 对应 dsh 的 ToolExecutionResult 的最小形态。
 * 调用身份保留在 ToolExecution 上, 结果只承载产出 (dsh: "结果仅承载产出")。
 * v1 的模型可见内容就是一段文本, isError=true 时 content 是模型可见的失败原因。
 */
public record ToolResult(String content, boolean isError) {

    public static ToolResult of(String content) {
        return new ToolResult(content, false);
    }

    public static ToolResult error(String message) {
        return new ToolResult(message, true);
    }
}
