package com.dsh.core.llm;

/**
 * 模型停止生成的原因, 对应 dsh FinishReasonMap 的最小子集。
 * TOOL_CALLS 表示模型要调工具, AgentLoop 需继续下一 step。
 */
public enum FinishReason {

    /**
     * 停止生成的原因
     */
    STOP,
    TOOL_CALLS,
    LENGTH,
    OTHER;

    /** OpenAI 兼容 finish_reason 字符串 -> 枚举, 未知值归入 OTHER。 */
    public static FinishReason fromWire(String value) {
        if (value == null) {
            return OTHER;
        }
        return switch (value) {
            case "stop" -> STOP;
            case "tool_calls", "function_call" -> TOOL_CALLS;
            case "length" -> LENGTH;
            default -> OTHER;
        };
    }
}
