package com.dsh.core.llm;

import com.dsh.config.DshProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实端点冒烟测试, 只有环境变量 ARK_API_KEY 存在时才执行 (CI/无 Key 本地自动跳过)。
 * 验证 DeepSeekLlmAdapter 对火山引擎 coding plan 端点的真实联通性,
 * 模型名取 application.yml 默认值 (dsh.llm.model)。
 */
@EnabledIfEnvironmentVariable(named = "ARK_API_KEY", matches = ".+")
class LlmSmokeTest {

    @Test
    void 真实端点应返回可解析的assistant消息() {
        DshProperties.Llm props = new DshProperties.Llm();
        // apiKey 走环境变量, model 与 application.yml 中 dsh.llm.model 保持一致
        props.setApiKey(System.getenv("ARK_API_KEY"));
        props.setModel("deepseek-v4-flash");
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofSeconds(10));
        factory.setReadTimeout(java.time.Duration.ofSeconds(props.getTimeoutSeconds()));
        RestClient.Builder builder = RestClient.builder().requestFactory(factory);

        LlmAdapter adapter = new DeepSeekLlmAdapter(props, new ObjectMapper(), builder);
        LlmResponse response = adapter.generate(LlmRequest.of(
                java.util.List.of(com.dsh.core.session.message.Message.of(
                        com.dsh.core.session.message.Role.USER, "只回复两个字: 收到"))));

        assertThat(response.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(response.message().content()).isNotEmpty();
        assertThat(response.usage().totalTokens()).isPositive();
        System.out.println("[smoke] 回复: " + response.message().content().get(0));
        System.out.println("[smoke] usage: " + response.usage());
    }
}
