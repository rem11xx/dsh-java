package com.dsh.core.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.dsh.core.session.event.SessionEvent;
import com.dsh.core.session.event.StepEndEvent;
import com.dsh.core.session.event.StepStartEvent;
import com.dsh.core.session.event.TurnEndEvent;
import com.dsh.core.session.event.TurnStartEvent;
import com.dsh.core.session.event.UserMessageEvent;
import com.dsh.core.session.message.Message;
import com.dsh.core.session.message.Role;
import com.dsh.core.session.message.TextBlock;
import com.dsh.core.session.message.ToolCallBlock;
import com.dsh.core.session.message.ToolResultBlock;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 会话聚合集成测试: 重放 roundtrip, deriveMessages 投影规则, 崩溃修复端到端。
 */
class SessionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void 一轮完整事件_close再open_事件一致() throws IOException {
        List<SessionEvent> before;
        try (var session = Session.open(tempDir, MAPPER)) {
            session.append(UserMessageEvent.draft(List.of(TextBlock.of("你好"))));
            session.append(TurnStartEvent.draft(1));
            session.append(StepStartEvent.draft(1, 1));
            session.append(StepEndEvent.draft(1, 1));
            session.append(TurnEndEvent.draft(1, TurnEndEvent.REASON_COMPLETED));
            before = session.events();
        }
        try (var session = Session.open(tempDir, MAPPER)) {
            assertThat(session.events()).isEqualTo(before);
            assertThat(session.id()).isNotBlank();
        }
    }

    @Test
    void deriveMessages_投影规则与dsh一致() {
        try (var session = Session.open(tempDir, MAPPER)) {
            session.append(TurnStartEvent.draft(1));
            session.append(StepStartEvent.draft(1, 1));
            // system 在首
            session.append(com.dsh.core.session.event.SystemMessageEvent.draft(1, 1, Message.of(Role.SYSTEM, "系统提示词")));
            session.append(UserMessageEvent.draft(List.of(TextBlock.of("用户问题"))));
            // assistant 带工具调用块
            session.append(com.dsh.core.session.event.AssistantMessageEvent.draft(1, 1,
                    new Message(Role.ASSISTANT, List.of(ToolCallBlock.of("call_1", "echo", "{}")))));
            // tool/call 不投影
            session.append(com.dsh.core.session.event.ToolCallEvent.draft(1, 1, "call_1", "echo", "{}"));
            // tool/result 投影为 user 消息
            session.append(com.dsh.core.session.event.ToolResultEvent.draft(1, 1, "call_1", "结果", false));
            // 空 assistant 内容跳过
            session.append(com.dsh.core.session.event.AssistantMessageEvent.draft(1, 1,
                    new Message(Role.ASSISTANT, List.of())));

            var messages = session.deriveMessages();
            assertThat(messages).hasSize(4); // system + user + assistant + tool-result(user)
            assertThat(messages.get(0).role()).isEqualTo(Role.SYSTEM);
            assertThat(messages.get(1).role()).isEqualTo(Role.USER);
            assertThat(messages.get(2).role()).isEqualTo(Role.ASSISTANT);
            assertThat(messages.get(3).role()).isEqualTo(Role.USER);
            assertThat(messages.get(3).content().get(0)).isInstanceOf(ToolResultBlock.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 崩溃修复端到端_重新open自动合成closer且可投影() throws IOException {
        // 模拟崩溃: 只写 turn/start + step/start + tool/call, 不闭合
        try (var session = Session.open(tempDir, MAPPER)) {
            session.append(TurnStartEvent.draft(1));
            session.append(StepStartEvent.draft(1, 1));
            session.append(com.dsh.core.session.event.ToolCallEvent.draft(1, 1, "call_1", "query", "{}"));
        }
        // 重新打开: 重放路径应自动追加 tool/result(isError) + step/end + turn/end(interrupted)
        try (var session = Session.open(tempDir, MAPPER)) {
            var events = session.events();
            assertThat(events).hasSize(6);
            assertThat(events.get(3)).isInstanceOf(com.dsh.core.session.event.ToolResultEvent.class);
            assertThat(events.get(4)).isInstanceOf(StepEndEvent.class);
            assertThat(((StepEndEvent) events.get(4)).data()).isEqualTo(new StepEndEvent.Data(1, 1));
            assertThat(events.get(5)).isInstanceOf(TurnEndEvent.class);
            assertThat(((TurnEndEvent) events.get(5)).data())
                    .isEqualTo(new TurnEndEvent.Data(1, TurnEndEvent.REASON_INTERRUPTED));
            // closer 的 seq 已正常分配
            assertThat(events.get(5).seq()).isEqualTo(5);
            // 修复结果可从投影中看到: isError 的 tool-result 作为 user 消息进入上下文
            var messages = session.deriveMessages();
            var last = (ToolResultBlock) messages.get(messages.size() - 1).content().get(0);
            assertThat(last.isError()).isTrue();
            // 再次重放: 日志已闭合, closer 不重复
        }
        try (var session = Session.open(tempDir, MAPPER)) {
            assertThat(session.events()).hasSize(6);
        }
    }

    @Test
    void 显式repair_与重启自动修复等效() throws IOException {
        try (var session = Session.open(tempDir, MAPPER)) {
            session.append(TurnStartEvent.draft(1));
            var appended = session.repair();
            assertThat(appended).hasSize(1); // 只有 turn/end(interrupted), 无 step 无 call
            assertThat(appended.get(0)).isInstanceOf(TurnEndEvent.class);
            assertThat(((TurnEndEvent) appended.get(0)).data())
                    .isEqualTo(new TurnEndEvent.Data(1, TurnEndEvent.REASON_INTERRUPTED));
        }
        try (var session = Session.open(tempDir, MAPPER)) {
            assertThat(session.events()).hasSize(2);
        }
    }
}
