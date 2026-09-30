package com.dsh.core.llm;

/**
 * LLM 调用失败 (配置缺失/网络/非 2xx 响应)。对应 dsh 的 LlmFailure:
 * 可序列化的失败事实, 由调用方决定是否重试, v1 不内置重试策略。
 */
public class LlmException extends RuntimeException {

    /** HTTP 状态码, 网络/配置错误时为 0 */
    private final int status;

    public LlmException(String message) {
        this(message, 0);
    }

    public LlmException(String message, int status) {
        super(message);
        this.status = status;
    }

    public LlmException(String message, int status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
