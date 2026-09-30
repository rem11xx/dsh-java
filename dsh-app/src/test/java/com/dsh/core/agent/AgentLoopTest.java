package com.dsh.core.agent;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import com.dsh.config.DshProperties;
import com.dsh.core.llm.FinishReason;
import com.dsh.core.llm.LlmAdapter;
import com.dsh.core.llm.LlmException;
import com.dsh.core.llm.LlmRequest;
import com.dsh.core.llm.LlmResponse;
import com.dsh.core.session.SessionManager;
import com.dsh.core.session.event.SessionEvent;
import com.dsh.core.session.message.Message;
import com.dsh.core.session.message.Role;
import com.dsh.core.session.message.TextBlock;
import com.dsh.core.session.message.ToolCallBlock;
import com.dsh.core.tools.Tool;
import com.dsh.core.tools.ToolRegistry;
import com.dsh.core.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AgentLoop 测试: 用脚本化 LlmAdapter 驱动状态机,
 * 核心断言是事件序列 -- 每次迭代必须能从日志独立重建。
 */
class AgentLoopTest {

    @TempDir
    Path dir;

    private final ObjectMapper mapper = new ObjectMapper();

    private DshProperties props;
    private ScriptedLlm llm;
    private RecordingTool tool;
    private SessionManager sessions;

    /** 假工具: 记录每次调用的参数, 可切换为抛异常模式 */
    static class RecordingTool implements Tool {

        private static final ObjectMapper MAPPER = new ObjectMapper();

        final List<JsonNode> invocations = new ArrayList<>();
        boolean failMode;

        @Override
        public String name() {
            return "fake_tool";
        }

        @Override
        public String description() {
            return "测试用假工具";
        }

        @Override
        public JsonNode parameters() {
            return MAPPER.createObjectNode().put("type", "object");
        }

        @Override
        public ToolResult execute(JsonNode args) {
            if (failMode) {
                throw new IllegalStateException("工具炸了");
            }
            invocations.add(args);
            return ToolResult.of("ok: " + args);
        }
    }

    /** 脚本化 LlmAdapter: 按序弹出预设响应, 记录收到的请求 */
    static class ScriptedLlm implements LlmAdapter {
        final ArrayDeque<LlmResponse> script = new ArrayDeque<>();
        final List<LlmRequest> requests = new ArrayList<>();

        @Override
        public String providerName() {
            return "scripted";
        }

        @Override
        public LlmResponse generate(LlmRequest request) {
            requests.add(request);
            LlmResponse next = script.poll();
            if (next == null) {
                throw new LlmException("脚本响应已耗尽");
            }
            return next;
        }
    }

    @BeforeEach
    void setUp() {
        props = new DshProperties();
        props.getSession().setDir(dir);
        llm = new ScriptedLlm();
        tool = new RecordingTool();
        sessions = new SessionManager(props, mapper);
    }

    private AgentLoop loop() {
        ToolRegistry tools = new ToolRegistry(mapper, List.of(tool), List.of());
        return new AgentLoop(sessions, llm, tools, props, event -> {
        });
    }

    private static LlmResponse textResponse(String text) {
        return new LlmResponse(Message.of(Role.ASSISTANT, text), FinishReason.STOP, null);
    }

    private static LlmResponse toolCallResponse(String callId, String args) {
        Message message = new Message(Role.ASSISTANT,
                List.of(ToolCallBlock.of(callId, "fake_tool", args)));
        return new LlmResponse(message, FinishReason.TOOL_CALLS, null);
    }

    private static List<String> types(List<SessionEvent> events) {
        return events.stream().map(SessionEvent::type).toList();
    }

    @Test
    void 完整轮次应产生合法事件序列并返回最终文本() {
        llm.script.add(toolCallResponse("call-1", "{\"x\":1}"));
        llm.script.add(textResponse("搞定了"));

        var result = loop().run("帮我看下");

        assertThat(result.reason()).isEqualTo("completed");
        assertThat(result.text()).isEqualTo("搞定了");
        // 事件序列: 一条不少, 一条不多 -- 这就是"可从日志独立重建"的直接证据
        assertThat(types(sessions.current().events())).containsExactly(
                "turn/start",
                "user/message",
                "step/start",
                "assistant/message",
                "tool/call",
                "tool/result",
                "step/end",
                "step/start",
                "assistant/message",
                "step/end",
                "turn/end");
        // 工具被真实执行了一次, 参数已解析
        assertThat(tool.invocations).hasSize(1);
        assertThat(tool.invocations.get(0).path("x").asInt()).isEqualTo(1);
    }

    @Test
    void 消息投影应包含工具往返且第二step请求携带结果() {
        llm.script.add(toolCallResponse("call-1", "{\"x\":1}"));
        llm.script.add(textResponse("done"));

        loop().run("问题");

        // 投影: user -> assistant(tool_call) -> user(tool_result) -> assistant(text)
        var messages = sessions.current().deriveMessages();
        assertThat(messages).hasSize(4);
        assertThat(messages.get(0).role()).isEqualTo(Role.USER);
        assertThat(messages.get(1).content()).containsExactly(ToolCallBlock.of("call-1", "fake_tool", "{\"x\":1}"));
        assertThat(messages.get(2).role()).isEqualTo(Role.USER);
        assertThat(messages.get(3).content()).containsExactly(TextBlock.of("done"));
        // 第二次请求把工具结果带给了模型, 且带上了工具 schema
        assertThat(llm.requests).hasSize(2);
        assertThat(llm.requests.get(1).messages()).hasSize(3);
        assertThat(llm.requests.get(1).tools()).hasSize(1);
        assertThat(llm.requests.get(1).tools().get(0).name()).isEqualTo("fake_tool");
    }

    @Test
    void 达到最大步数应强制闭合turn() {
        props.getAgent().setMaxSteps(2);
        llm.script.add(toolCallResponse("call-1", "{}"));
        llm.script.add(toolCallResponse("call-2", "{}"));
        llm.script.add(textResponse("不该走到这里"));

        var result = loop().run("停不下来的问题");

        assertThat(result.reason()).isEqualTo("max_steps");
        assertThat(result.text()).isEmpty();
        var events = sessions.current().events();
        assertThat(types(events).stream().filter("tool/call"::equals).count()).isEqualTo(2);
        assertThat(events.get(events.size() - 1).type()).isEqualTo("turn/end");
        assertThat(((com.dsh.core.session.event.TurnEndEvent) events.get(events.size() - 1)).data().reason())
                .isEqualTo("max_steps");
    }

    @Test
    void LLM异常应向上抛且turn以error闭合() {
        llm.script.clear(); // generate 直接抛 LlmException

        assertThatThrownBy(() -> loop().run("会失败的问题"))
                .isInstanceOf(LlmException.class);

        var events = sessions.current().events();
        // 崩溃现场: step/start 后无 step/end, 由 turn/end error 闭合, 重启后可被重放修复
        assertThat(types(events)).containsExactly(
                "turn/start", "user/message", "step/start", "turn/end");
        assertThat(((com.dsh.core.session.event.TurnEndEvent) events.get(events.size() - 1)).data().reason())
                .isEqualTo("error");
    }

    @Test
    void 工具失败不终止轮次而是交还模型() {
        tool.failMode = true;
        llm.script.add(toolCallResponse("call-1", "{}"));
        llm.script.add(textResponse("看到工具报错后我改用文本回答"));

        var result = loop().run("工具会炸");

        assertThat(result.reason()).isEqualTo("completed");
        // tool/result 事件带 isError, 模型据此决策
        var resultEvent = sessions.current().events().stream()
                .filter(com.dsh.core.session.event.ToolResultEvent.class::isInstance)
                .map(com.dsh.core.session.event.ToolResultEvent.class::cast)
                .findFirst().orElseThrow();
        assertThat(resultEvent.data().message().content().get(0))
                .isInstanceOf(com.dsh.core.session.message.ToolResultBlock.class)
                .extracting(b -> ((com.dsh.core.session.message.ToolResultBlock) b).isError())
                .isEqualTo(true);
    }

    @Test
    void 系统提示词应只落一次且进入模型请求() {
        props.getAgent().setSystemPrompt("你是测试助手");
        llm.script.add(textResponse("第一轮"));
        llm.script.add(textResponse("第二轮"));

        loop().run("第一问");
        loop().run("第二问");

        long systemCount = sessions.current().events().stream()
                .filter(com.dsh.core.session.event.SystemMessageEvent.class::isInstance)
                .count();
        assertThat(systemCount).isEqualTo(1);
        // 第二轮请求的第一条消息是系统提示词 (模型可见即已记录)
        var first = llm.requests.get(1).messages().get(0);
        assertThat(first.role()).isEqualTo(Role.SYSTEM);
        assertThat(first.content()).containsExactly(TextBlock.of("你是测试助手"));
    }

    @Test
    void turn编号应从日志递增而不依赖内存() {
        llm.script.add(textResponse("a"));
        llm.script.add(textResponse("b"));

        loop().run("第一问");
        var result = loop().run("第二问");

        assertThat(result.turn()).isEqualTo(2);
    }

    @Test
    void 一次assistant消息带多个tool_call_逐个执行且事件成对() {
        Message multi = new Message(Role.ASSISTANT, List.of(
                ToolCallBlock.of("call-1", "fake_tool", "{\"x\":1}"),
                ToolCallBlock.of("call-2", "fake_tool", "{\"x\":2}")));
        llm.script.add(new LlmResponse(multi, FinishReason.TOOL_CALLS, null));
        llm.script.add(textResponse("两件事都做完了"));

        var result = loop().run("并行做两件事");

        assertThat(result.reason()).isEqualTo("completed");
        assertThat(tool.invocations).hasSize(2);
        assertThat(tool.invocations.get(0).path("x").asInt()).isEqualTo(1);
        assertThat(tool.invocations.get(1).path("x").asInt()).isEqualTo(2);
        // 每个调用都有配对的 call/result 事件, 顺序与模型给出的一致
        var events = sessions.current().events();
        assertThat(types(events)).containsSubsequence(
                "tool/call", "tool/result", "tool/call", "tool/result");
        var callIds = events.stream()
                .filter(com.dsh.core.session.event.ToolCallEvent.class::isInstance)
                .map(e -> ((com.dsh.core.session.event.ToolCallEvent) e).data().callId())
                .toList();
        assertThat(callIds).containsExactly("call-1", "call-2");
    }

    @Test
    void 模型调用未注册工具_错误结果交还模型_轮次不终止() {
        Message unknown = new Message(Role.ASSISTANT, List.of(
                ToolCallBlock.of("call-1", "no_such_tool", "{}")));
        llm.script.add(new LlmResponse(unknown, FinishReason.TOOL_CALLS, null));
        llm.script.add(textResponse("该工具不存在, 我直接回答"));

        var result = loop().run("调用不存在的工具");

        assertThat(result.reason()).isEqualTo("completed");
        assertThat(result.text()).isEqualTo("该工具不存在, 我直接回答");
        assertThat(tool.invocations).as("假工具不应被波及").isEmpty();
        var resultEvent = sessions.current().events().stream()
                .filter(com.dsh.core.session.event.ToolResultEvent.class::isInstance)
                .map(com.dsh.core.session.event.ToolResultEvent.class::cast)
                .findFirst().orElseThrow();
        assertThat(((com.dsh.core.session.message.ToolResultBlock)
                resultEvent.data().message().content().get(0)).isError()).isTrue();
        // 第二个请求把错误结果带给了模型, 模型据此改用文本回答
        var secondRequest = llm.requests.get(1);
        assertThat(((com.dsh.core.session.message.ToolResultBlock)
                secondRequest.messages().get(2).content().get(0)).isError()).isTrue();
    }

    @Test
    void LLM第二步崩溃_日志error闭合_重开自动修复且turn编号续接() {
        props.getAgent().setMaxSteps(3);
        // 第一步正常完成工具往返, 第二步脚本耗尽 -> generate 抛 LlmException
        llm.script.add(toolCallResponse("call-1", "{}"));

        assertThatThrownBy(() -> loop().run("中途会崩的问题"))
                .isInstanceOf(LlmException.class);

        var events = sessions.current().events();
        // 崩溃现场: 第一步完整闭合, 第二步只有 step/start, 由 turn/end(error) 收尾
        assertThat(types(events)).containsExactly(
                "turn/start", "user/message",
                "step/start", "assistant/message", "tool/call", "tool/result", "step/end",
                "step/start", "turn/end");
        assertThat(((com.dsh.core.session.event.TurnEndEvent) events.get(events.size() - 1)).data().reason())
                .isEqualTo("error");

        // 模拟跨进程重启: 全新 SessionManager 打开同一目录, 日志已闭合则 repair 不追加
        var reopened = new SessionManager(props, mapper);
        assertThat(reopened.current().events()).hasSize(events.size());
        // 崩溃轮的工具往返在投影中完整保留, 下一轮请求上下文不丢
        var messages = reopened.current().deriveMessages();
        assertThat(messages).hasSize(3); // user + assistant(tool_call) + user(tool_result)
        // 接着跑: turn 编号从日志续接为 2, 与崩溃前的内存状态无关
        llm.script.add(textResponse("恢复后继续"));
        var next = new AgentLoop(reopened, llm, new ToolRegistry(mapper, List.of(tool), List.of()),
                props, event -> {
        }).run("重启后的问题");
        assertThat(next.turn()).isEqualTo(2);
        assertThat(next.reason()).isEqualTo("completed");
    }
}
