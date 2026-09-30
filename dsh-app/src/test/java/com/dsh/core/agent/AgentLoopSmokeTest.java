package com.dsh.core.agent;

import java.nio.file.Files;
import java.util.List;

import com.dsh.config.DshProperties;
import com.dsh.core.llm.DeepSeekLlmAdapter;
import com.dsh.core.llm.LlmAdapter;
import com.dsh.core.session.SessionManager;
import com.dsh.core.tools.ToolRegistry;
import com.dsh.core.tools.builtin.EchoTool;
import com.dsh.core.tools.intercept.ToolLoggingInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D5 端到端冒烟: 真实 LLM + 真实工具注册表 + session 落盘, 走完一个带工具调用的完整轮次。
 * 只有环境变量 ARK_API_KEY 存在时才执行 (无 Key 本地自动跳过), 与 LlmSmokeTest 同一门禁。
 */
@EnabledIfEnvironmentVariable(named = "ARK_API_KEY", matches = ".+")
class AgentLoopSmokeTest {

    @Test
    void 端到端应完成一次带工具调用的轮次() throws Exception {
        DshProperties props = new DshProperties();
        props.getSession().setDir(Files.createTempDirectory("dsh-smoke"));
        props.getLlm().setApiKey(System.getenv("ARK_API_KEY"));
        props.getLlm().setModel("deepseek-v4-flash");
        props.getAgent().setSystemPrompt("你是 dsh-java 的演示助手, 必须按用户要求调用工具。");

        ObjectMapper mapper = new ObjectMapper();
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofSeconds(10));
        factory.setReadTimeout(java.time.Duration.ofSeconds(props.getLlm().getTimeoutSeconds()));
        LlmAdapter llm = new DeepSeekLlmAdapter(props.getLlm(), mapper,
                org.springframework.web.client.RestClient.builder().requestFactory(factory));
        ToolRegistry tools = new ToolRegistry(mapper, List.of(new EchoTool()),
                List.of(new ToolLoggingInterceptor()));
        SessionManager sessions = new SessionManager(props, mapper);
        AgentLoop loop = new AgentLoop(sessions, llm, tools, props, event -> {
        });

        AgentLoop.TurnResult result = loop.run(
                "请调用 echo 工具, 参数 text 为 \"hello-dsh\", 然后原样告诉我工具返回了什么。");

        assertThat(result.reason()).isEqualTo("completed");
        assertThat(result.text()).contains("hello-dsh");
        // 日志里必须能看到完整的工具往返
        var types = sessions.current().events().stream().map(e -> e.type()).toList();
        assertThat(types).containsSubsequence("turn/start", "assistant/message",
                "tool/call", "tool/result", "turn/end");
        System.out.println("[smoke] 最终回复: " + result.text());
        System.out.println("[smoke] 事件序列: " + types);
    }
}
