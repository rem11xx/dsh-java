package com.dsh.core.llm;

/**
 * LLM 适配器 seam, 对应 dsh 的 LlmAdapter + ctx.llm 注册表。
 * 核心只依赖此接口; 换 provider = 换一个实现 bean, 不改 AgentLoop
 * v1 非流式: 单次请求同步返回完整响应。
 */
public interface LlmAdapter {

    /** provider 路由名, 对应 dsh 的 provider id, 用于诊断与展示。 */
    String providerName();

    /**
     * 执行一次模型调用。失败抛 LlmException, 不返回半成品响应。
     */
    LlmResponse generate(LlmRequest request);
}
