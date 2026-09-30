package com.dsh.plugin.stub;

import java.util.List;

import com.dsh.core.llm.FinishReason;
import com.dsh.core.llm.LlmRequest;
import com.dsh.core.llm.LlmResponse;
import com.dsh.core.llm.ToolSchema;
import com.dsh.core.session.message.Message;
import com.dsh.core.session.message.Role;
import com.dsh.core.session.message.TextBlock;
import com.dsh.core.session.message.ToolCallBlock;
import com.dsh.core.session.message.ToolResultBlock;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 剧本适配器的单元测试: 三个分支 (发起调用 / 引用结果收尾 / 工具不在册)。
 */
class StubLlmAdapterTest {

    private final StubLlmAdapter adapter = new StubLlmAdapter("now");

    private static final List<ToolSchema> NOW_TOOLS = List.of(new ToolSchema("now", "时间", null));

    @Test
    void 新用户消息且工具在册_返回工具调用() {
        var request = new LlmRequest(List.of(new Message(Role.USER, List.of(TextBlock.of("现在几点")))), NOW_TOOLS);

        LlmResponse response = adapter.generate(request);

        assertThat(response.finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
        var call = (ToolCallBlock) response.message().content().get(0);
        assertThat(call.name()).isEqualTo("now");
        assertThat(call.callId()).isEqualTo("stub-call-1");
    }

    @Test
    void 最后一条消息是工具结果_引用结果收尾() {
        var request = new LlmRequest(List.of(
                new Message(Role.USER, List.of(TextBlock.of("现在几点"))),
                new Message(Role.ASSISTANT, List.of(ToolCallBlock.of("stub-call-1", "now", "{}"))),
                new Message(Role.USER, List.of(ToolResultBlock.of("stub-call-1",
                        List.of(TextBlock.of("2026-09-18 22:00:00")), false)))),
                NOW_TOOLS);

        LlmResponse response = adapter.generate(request);

        assertThat(response.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(((TextBlock) response.message().content().get(0)).text())
                .contains("now").contains("2026-09-18 22:00:00");
    }

    @Test
    void 历史里有旧工具结果_新一轮仍会发起调用() {
        // 上一轮的完整闭环留在历史里, 新用户输入是最后一条消息, 剧本应重新开始
        var request = new LlmRequest(List.of(
                new Message(Role.USER, List.of(TextBlock.of("几点"))),
                new Message(Role.ASSISTANT, List.of(ToolCallBlock.of("stub-call-1", "now", "{}"))),
                new Message(Role.USER, List.of(ToolResultBlock.of("stub-call-1",
                        List.of(TextBlock.of("22:00")), false))),
                new Message(Role.ASSISTANT, List.of(TextBlock.of("22:00"))),
                new Message(Role.USER, List.of(TextBlock.of("再问一次几点")))),
                NOW_TOOLS);

        LlmResponse response = adapter.generate(request);

        assertThat(response.finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
    }

    @Test
    void 工具不在册_直接收尾不调用() {
        var request = new LlmRequest(List.of(new Message(Role.USER, List.of(TextBlock.of("hi")))), List.of());

        LlmResponse response = adapter.generate(request);

        assertThat(response.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(((TextBlock) response.message().content().get(0)).text()).contains("不在");
    }
}
