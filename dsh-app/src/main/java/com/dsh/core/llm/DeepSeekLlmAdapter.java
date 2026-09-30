package com.dsh.core.llm;

import com.dsh.config.DshProperties;
import com.dsh.core.session.message.ContentBlock;
import com.dsh.core.session.message.Message;
import com.dsh.core.session.message.Role;
import com.dsh.core.session.message.TextBlock;
import com.dsh.core.session.message.ToolCallBlock;
import com.dsh.core.session.message.ToolResultBlock;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 默认 LlmAdapter 实现, 走 OpenAI 兼容的 chat/completions 协议
 * (火山引擎 coding plan 端点, 对应 dsh 的 ChatCompletionsAdapter)。
 *
 * 职责只有两件事: 把 session 的 Message 模型翻译成 wire 格式, 再把响应翻译回来。
 * v1 非流式, 不内置重试 (dsh 的 retry-policy 留作扩展点)。
 */
public class DeepSeekLlmAdapter implements LlmAdapter {

    private static final String PROVIDER = "deepseek";

    private final DshProperties.Llm props;
    private final ObjectMapper mapper;
    private final RestClient client;
    private final String endpoint;

    public DeepSeekLlmAdapter(DshProperties.Llm props, ObjectMapper mapper, RestClient.Builder builder) {
        this.props = props;
        this.mapper = mapper;
        String base = props.getBaseUrl() == null ? "" : props.getBaseUrl().trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.endpoint = base + "/chat/completions";
        // 超时等传输策略由装配方通过 builder 注入, 适配器只负责协议翻译
        this.client = builder.build();
    }

    @Override
    public String providerName() {
        return PROVIDER;
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        requireConfig();
        WireRequest wire = toWire(request);
        WireResponse response = post(wire);
        return fromWire(response);
    }

    // ------------------------------------------------------------------
    // 模型 -> wire: Message 体系翻译成 OpenAI chat/completions 格式
    // ------------------------------------------------------------------

    private WireRequest toWire(LlmRequest request) {
        List<WireMessage> messages = new ArrayList<>();
        for (Message message : request.messages()) {
            switch (message.role()) {
                case SYSTEM -> messages.add(new WireMessage("system", joinText(message), null, null));
                case ASSISTANT -> messages.add(new WireMessage("assistant", textOrNull(message),
                        toWireToolCalls(message), null));
                case USER -> toWireUser(message, messages);
            }
        }
        List<WireTool> tools = request.tools().stream()
                .map(schema -> new WireTool("function",
                        new WireFunctionSpec(schema.name(), schema.description(), schema.parameters())))
                .toList();
        return new WireRequest(props.getModel(), messages, tools.isEmpty() ? null : tools);
    }

    /**
     * user 消息里可能是纯文本, 也可能带 ToolResultBlock。
     * OpenAI 协议里工具结果是独立的 role=tool 消息, 按出现顺序展开。
     */
    private void toWireUser(Message message, List<WireMessage> out) {
        List<ToolResultBlock> results = message.content().stream()
                .filter(ToolResultBlock.class::isInstance)
                .map(ToolResultBlock.class::cast)
                .toList();
        String text = joinText(message);
        if (results.isEmpty()) {
            out.add(new WireMessage("user", text, null, null));
            return;
        }
        // 有工具结果时, 文本部分非空才发 user 消息
        if (!text.isEmpty()) {
            out.add(new WireMessage("user", text, null, null));
        }
        for (ToolResultBlock result : results) {
            out.add(new WireMessage("tool", serializeResultContent(result), null, result.callId()));
        }
    }

    private List<WireToolCall> toWireToolCalls(Message message) {
        List<WireToolCall> calls = message.content().stream()
                .filter(ToolCallBlock.class::isInstance)
                .map(ToolCallBlock.class::cast)
                .map(call -> new WireToolCall(call.callId(), "function",
                        new WireFunction(call.name(), call.arguments())))
                .collect(Collectors.toList());
        return calls.isEmpty() ? null : calls;
    }

    /** 工具结果内容: 单个 text 块直接传文本, 否则整体 JSON 序列化 (OpenAI 的 content 是字符串)。 */
    private String serializeResultContent(ToolResultBlock result) {
        if (result.content().size() == 1 && result.content().get(0) instanceof TextBlock text) {
            return text.text();
        }
        try {
            return mapper.writeValueAsString(result.content());
        } catch (JsonProcessingException e) {
            // 序列化自家模型不该失败, 兜底为错误标记, 不吞掉调用
            return "{\"error\":\"unserializable tool result\"}";
        }
    }

    private String joinText(Message message) {
        return message.content().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::text)
                .collect(Collectors.joining());
    }

    /** assistant 消息的 content 字段: 纯文本为 null 时表示只有 tool_calls。 */
    private String textOrNull(Message message) {
        String text = joinText(message);
        return text.isEmpty() ? null : text;
    }

    // ------------------------------------------------------------------
    // wire -> 模型: 响应翻译回 Message 体系
    // ------------------------------------------------------------------

    private LlmResponse fromWire(WireResponse response) {
        if (response.choices() == null || response.choices().isEmpty()) {
            throw new LlmException("LLM 响应缺少 choices");
        }
        WireChoice choice = response.choices().get(0);
        WireAssistantMessage message = choice.message();
        List<ContentBlock> blocks = new ArrayList<>();
        if (message.content() != null && !message.content().isBlank()) {
            blocks.add(TextBlock.of(message.content()));
        }
        if (message.toolCalls() != null) {
            for (WireToolCall call : message.toolCalls()) {
                if (call.function() == null) {
                    continue;
                }
                blocks.add(ToolCallBlock.of(call.id(), call.function().name(), call.function().arguments()));
            }
        }
        TokenUsage usage = toUsage(response.usage());
        return new LlmResponse(new Message(Role.ASSISTANT, blocks),
                FinishReason.fromWire(choice.finishReason()), usage);
    }

    private TokenUsage toUsage(WireUsage usage) {
        if (usage == null) {
            return TokenUsage.ZERO;
        }
        return new TokenUsage(nz(usage.promptTokens()), nz(usage.completionTokens()), nz(usage.totalTokens()));
    }

    private static long nz(Long value) {
        return value == null ? 0 : value;
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    private WireResponse post(WireRequest wire) {
        String body;
        try {
            body = mapper.writeValueAsString(wire);
        } catch (JsonProcessingException e) {
            throw new LlmException("LLM 请求序列化失败", 0, e);
        }
        try {
            return client.post()
                    .uri(endpoint)
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            throw new LlmException(errorSummary(response.getStatusCode(), response.getBody()),
                                    response.getStatusCode().value());
                        }
                        return mapper.readValue(response.getBody(), WireResponse.class);
                    });
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("LLM 调用失败: " + e.getMessage(), 0, e);
        }
    }

    private String errorSummary(HttpStatusCode status, java.io.InputStream body) {
        try {
            String text = new String(body.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            JsonNode node = mapper.readTree(text);
            // OpenAI 兼容错误体: {"error": {"message": ...}}
            if (node.path("error").path("message").isTextual()) {
                return "LLM 返回 " + status.value() + ": " + node.path("error").path("message").asText();
            }
            return "LLM 返回 " + status.value() + ": " + text;
        } catch (Exception e) {
            return "LLM 返回 " + status.value() + " (响应体不可读)";
        }
    }

    private void requireConfig() {
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            throw new LlmException("缺少 API Key: 请设置环境变量 ARK_API_KEY (或配置 dsh.llm.api-key)");
        }
        if (props.getModel() == null || props.getModel().isBlank()) {
            throw new LlmException("缺少模型名: 请设置环境变量 ARK_MODEL (或配置 dsh.llm.model)");
        }
    }

    // ------------------------------------------------------------------
    // OpenAI 兼容 wire DTO。字段名与协议一致, 仅作传输用。
    // ------------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record WireRequest(String model, List<WireMessage> messages, List<WireTool> tools) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireMessage(String role, String content, @JsonProperty("tool_calls") List<WireToolCall> toolCalls,
                       @JsonProperty("tool_call_id") String toolCallId) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireTool(String type, WireFunctionSpec function) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record WireFunctionSpec(String name, String description, JsonNode parameters) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireToolCall(String id, String type, WireFunction function) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireFunction(String name, String arguments) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireResponse(List<WireChoice> choices, WireUsage usage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireChoice(WireAssistantMessage message, @JsonProperty("finish_reason") String finishReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireAssistantMessage(String content, @JsonProperty("tool_calls") List<WireToolCall> toolCalls) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireUsage(@JsonProperty("prompt_tokens") Long promptTokens,
                     @JsonProperty("completion_tokens") Long completionTokens,
                     @JsonProperty("total_tokens") Long totalTokens) {
    }
}
