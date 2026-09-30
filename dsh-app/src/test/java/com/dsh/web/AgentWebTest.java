package com.dsh.web;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;

import com.dsh.core.llm.FinishReason;
import com.dsh.core.llm.LlmAdapter;
import com.dsh.core.llm.LlmException;
import com.dsh.core.llm.LlmRequest;
import com.dsh.core.llm.LlmResponse;
import com.dsh.core.session.SessionManager;
import com.dsh.core.session.event.SessionEvent;
import com.dsh.core.session.event.TurnStartEvent;
import com.dsh.core.session.message.Message;
import com.dsh.core.session.message.Role;
import com.dsh.core.session.message.TextBlock;
import com.dsh.core.session.message.ToolCallBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D6 Web 层集成测试: 脚本化 LLM 走真实 HTTP 栈,
 * 覆盖 agent 轮次端点 / SSE 实时推送 / SSE 历史补发三条主路径。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AgentWebTest {

    @TempDir
    static Path dir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("dsh.session.dir", () -> dir.toAbsolutePath().toString());
    }

    /** 脚本化 LLM, @Primary 顶替真实 DeepSeek 适配器, 不发真实请求 */
    @TestConfiguration
    static class StubConfig {

        @Bean
        @Primary
        ScriptedLlm stubLlm() {
            return new ScriptedLlm();
        }
    }

    static class ScriptedLlm implements LlmAdapter {
        final ArrayDeque<LlmResponse> script = new ArrayDeque<>();

        @Override
        public String providerName() {
            return "scripted";
        }

        @Override
        public LlmResponse generate(LlmRequest request) {
            LlmResponse next = script.poll();
            if (next == null) {
                throw new LlmException("脚本响应已耗尽");
            }
            return next;
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ScriptedLlm llm;
    @Autowired
    private SseEventBroadcaster broadcaster;
    @Autowired
    private ApplicationEventPublisher publisher;
    @Autowired
    private SessionManager sessions;

    @BeforeEach
    void resetScript() {
        llm.script.clear();
    }

    private static LlmResponse textResponse(String text) {
        return new LlmResponse(Message.of(Role.ASSISTANT, text), FinishReason.STOP, null);
    }

    private static LlmResponse toolCallResponse(String callId, String args) {
        Message message = new Message(Role.ASSISTANT, List.of(ToolCallBlock.of(callId, "echo", args)));
        return new LlmResponse(message, FinishReason.TOOL_CALLS, null);
    }

    @Test
    void 演示页应可访问() throws Exception {
        mvc.perform(get("/index.html")).andExpect(status().isOk());
    }

    @Test
    void 轮次端点应执行工具循环并返回最终文本() throws Exception {
        llm.script.add(toolCallResponse("call-1", "{\"message\":\"hi\"}"));
        llm.script.add(textResponse("工具跑完了"));

        mvc.perform(post("/api/agent/turn")
                        .contentType("application/json")
                        .content("{\"text\":\"调用一下工具\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("completed"))
                .andExpect(jsonPath("$.text").value("工具跑完了"));

        // 真实工具被真实执行, 事件落日志
        List<String> types = sessions.current().events().stream().map(SessionEvent::type).toList();
        assertThat(types).containsSubsequence("tool/call", "tool/result");
        assertThat(types.get(types.size() - 1)).isEqualTo("turn/end");
    }

    @Test
    void 空文本应返回400() throws Exception {
        mvc.perform(post("/api/agent/turn")
                        .contentType("application/json")
                        .content("{\"text\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void LLM失败应返回502且轮次以error闭合() throws Exception {
        // script 为空, generate 直接抛 LlmException

        mvc.perform(post("/api/agent/turn")
                        .contentType("application/json")
                        .content("{\"text\":\"会失败的问题\"}"))
                .andExpect(status().is(502))
                .andExpect(jsonPath("$.error").exists());

        assertThat(sessions.current().events().get(sessions.current().events().size() - 1).type()).isEqualTo("turn/end");
    }

    @Test
    void SSE流应实时收到广播事件() throws Exception {
        MvcResult async = mvc.perform(get("/api/events/stream"))
                .andExpect(request().asyncStarted())
                .andReturn();

        // 从"别的线程" (这里就是测试线程) 广播一条事件, 模拟轮次进行中的推送
        publisher.publishEvent(sessions.current().append(TurnStartEvent.draft(99)));

        broadcaster.completeAll();
        mvc.perform(asyncDispatch(async)).andExpect(status().isOk());

        String body = async.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("event:turn/start");
        assertThat(body).contains("\"turn\":99");
    }

    @Test
    void SSE流应按sinceSeq补发历史事件() throws Exception {
        // 先落两条历史事件
        var session = sessions.current();
        long since = session.append(TurnStartEvent.draft(1)).seq();
        session.append(com.dsh.core.session.event.UserMessageEvent
                .draft(List.of(TextBlock.of("历史消息"))));

        MvcResult async = mvc.perform(get("/api/events/stream").param("sinceSeq", String.valueOf(since)))
                .andExpect(request().asyncStarted())
                .andReturn();

        broadcaster.completeAll();
        mvc.perform(asyncDispatch(async)).andExpect(status().isOk());

        String body = async.getResponse().getContentAsString(StandardCharsets.UTF_8);
        // 只补发 sinceSeq 之后的事件: 有 user/message, 没有 turn/start
        assertThat(body).contains("event:user/message");
        assertThat(body).contains("历史消息");
        assertThat(body).doesNotContain("event:turn/start");
    }
}
