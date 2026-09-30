package com.dsh.core.llm;

import com.dsh.config.DshProperties;
import com.dsh.core.session.message.Message;
import com.dsh.core.session.message.TextBlock;
import com.dsh.core.session.message.ToolCallBlock;
import com.dsh.core.session.message.ToolResultBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * DeepSeekLlmAdapter 测试: 用 MockRestServiceServer 模拟 OpenAI 兼容端点,
 * 重点验证 Message 体系 <-> wire 格式的双向翻译 (PPT 关键实现之一)。
 */
class DeepSeekLlmAdapterTest {

    private static final String ENDPOINT = "https://ark.example/api/coding/v3/chat/completions";

    private DshProperties.Llm props;
    private MockRestServiceServer server;
    private DeepSeekLlmAdapter adapter;

    @BeforeEach
    void setUp() {
        props = new DshProperties.Llm();
        props.setBaseUrl("https://ark.example/api/coding/v3");
        props.setApiKey("test-key");
        props.setModel("test-model");
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new DeepSeekLlmAdapter(props, new ObjectMapper(), builder);
    }

    @Test
    void 消息体系应翻译成OpenAiWire格式() throws Exception {
        server.expect(requestTo(ENDPOINT))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.model").value("test-model"))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content").value("你是助手"))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content").value("看下天气"))
                .andExpect(jsonPath("$.messages[2].role").value("assistant"))
                .andExpect(jsonPath("$.messages[2].content").doesNotExist())
                .andExpect(jsonPath("$.messages[2].tool_calls[0].id").value("call-1"))
                .andExpect(jsonPath("$.messages[2].tool_calls[0].function.name").value("get_weather"))
                .andExpect(jsonPath("$.messages[3].role").value("tool"))
                .andExpect(jsonPath("$.messages[3].tool_call_id").value("call-1"))
                .andExpect(jsonPath("$.messages[3].content").value("晴, 26 度"))
                .andExpect(jsonPath("$.tools[0].function.name").value("get_weather"))
                .andRespond(withSuccess("{\"choices\":[]}", APPLICATION_JSON));

        LlmRequest request = new LlmRequest(List.of(
                Message.of(com.dsh.core.session.message.Role.SYSTEM, "你是助手"),
                Message.of(com.dsh.core.session.message.Role.USER, "看下天气"),
                new Message(com.dsh.core.session.message.Role.ASSISTANT,
                        List.of(ToolCallBlock.of("call-1", "get_weather", "{\"city\":\"北京\"}"))),
                new Message(com.dsh.core.session.message.Role.USER,
                        List.of(ToolResultBlock.of("call-1",
                                List.of(TextBlock.of("晴, 26 度")), false)))
        ), List.of(new ToolSchema("get_weather", "查询天气",
                new ObjectMapper().readTree("{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}"))));

        assertThatThrownBy(() -> adapter.generate(request))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("choices");
        server.verify();
    }

    @Test
    void 纯文本响应应解析成assistant消息() {
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess("""
                        {
                          "choices": [{
                            "message": {"role": "assistant", "content": "你好, 有什么可以帮你?"},
                            "finish_reason": "stop"
                          }],
                          "usage": {"prompt_tokens": 12, "completion_tokens": 8, "total_tokens": 20}
                        }
                        """, APPLICATION_JSON));

        LlmResponse response = adapter.generate(LlmRequest.of(List.of(
                Message.of(com.dsh.core.session.message.Role.USER, "你好"))));

        assertThat(response.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(response.message().content()).hasSize(1);
        assertThat(response.message().content().get(0)).isEqualTo(TextBlock.of("你好, 有什么可以帮你?"));
        assertThat(response.usage()).isEqualTo(new TokenUsage(12, 8, 20));
    }

    @Test
    void 工具调用响应应解析成ToolCallBlock() {
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess("""
                        {
                          "choices": [{
                            "message": {
                              "role": "assistant",
                              "content": null,
                              "tool_calls": [{
                                "id": "call-9",
                                "type": "function",
                                "function": {"name": "get_weather", "arguments": "{\\"city\\":\\"北京\\"}"}
                              }]
                            },
                            "finish_reason": "tool_calls"
                          }],
                          "usage": {"prompt_tokens": 30, "completion_tokens": 5, "total_tokens": 35}
                        }
                        """, APPLICATION_JSON));

        LlmResponse response = adapter.generate(LlmRequest.of(List.of(
                Message.of(com.dsh.core.session.message.Role.USER, "北京天气"))));

        assertThat(response.finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
        assertThat(response.message().content()).containsExactly(
                ToolCallBlock.of("call-9", "get_weather", "{\"city\":\"北京\"}"));
    }

    @Test
    void 非成功状态码应抛LlmException并带状态() {
        server.expect(requestTo(ENDPOINT))
                .andRespond(withServerError().body("{\"error\":{\"message\":\"api key invalid\"}}"));

        assertThatThrownBy(() -> adapter.generate(LlmRequest.of(List.of(
                Message.of(com.dsh.core.session.message.Role.USER, "hi")))))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("api key invalid")
                .extracting(e -> ((LlmException) e).status())
                .isEqualTo(500);
    }

    @Test
    void 缺少APIKey应抛配置异常() {
        props.setApiKey("");
        assertThatThrownBy(() -> adapter.generate(LlmRequest.of(List.of(
                Message.of(com.dsh.core.session.message.Role.USER, "hi")))))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("ARK_API_KEY");
    }

    @Test
    void 多内容块工具结果应整体JSON序列化() {
        server.expect(requestTo(ENDPOINT))
                .andExpect(jsonPath("$.messages[1].role").value("tool"))
                .andExpect(jsonPath("$.messages[1].content").value(
                        "[{\"type\":\"text\",\"text\":\"a\"},{\"type\":\"text\",\"text\":\"b\"}]"))
                .andRespond(withSuccess("{\"choices\":[]}", APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.generate(LlmRequest.of(List.of(
                Message.of(com.dsh.core.session.message.Role.USER, "x"),
                new Message(com.dsh.core.session.message.Role.USER, List.of(ToolResultBlock.of("call-1",
                        List.of(TextBlock.of("a"), TextBlock.of("b")), false)))))))
                .isInstanceOf(LlmException.class);

        server.verify();
    }
}
