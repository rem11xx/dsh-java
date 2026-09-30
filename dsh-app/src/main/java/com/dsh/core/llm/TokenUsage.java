package com.dsh.core.llm;

/**
 * 一次调用的 token 计数。v1 只保留总量, 不拆 dsh 的 cache 读写/reasoning 分项
 * (OpenAI 兼容端点的 usage 也不稳定提供这些字段)。
 */
public record TokenUsage(long inputTokens, long outputTokens, long totalTokens) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0);
}
