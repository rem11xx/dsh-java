package com.dsh.core.llm;

import com.dsh.config.DshProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * LLM seam 装配。默认注册 DeepSeek 适配器; 任何模块声明自己的 LlmAdapter bean
 * 即可整体替换 AgentLoop 与配置不用动 —— 换实现不改核心。
 */
@Configuration
public class LlmAdapterConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmAdapterConfig.class);

    @Bean
    @ConditionalOnMissingBean(LlmAdapter.class)
    public LlmAdapter llmAdapter(DshProperties properties, ObjectMapper mapper) {
        DshProperties.Llm llm = properties.getLlm();
        if (llm.getApiKey() == null || llm.getApiKey().isBlank()) {
            log.warn("未配置 API Key (ARK_API_KEY), LLM 调用将失败; 本地演示可先跑 session/工具部分");
        }
        if (llm.getModel() == null || llm.getModel().isBlank()) {
            log.warn("未配置模型名 (ARK_MODEL), LLM 调用将失败");
        }
        return new DeepSeekLlmAdapter(llm, mapper, restClientBuilder(llm));
    }

    /** 传输层策略 (超时) 集中在这里, 适配器本身不绑定具体 client。 */
    private RestClient.Builder restClientBuilder(DshProperties.Llm llm) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(llm.getTimeoutSeconds()));
        return RestClient.builder().requestFactory(factory);
    }
}
