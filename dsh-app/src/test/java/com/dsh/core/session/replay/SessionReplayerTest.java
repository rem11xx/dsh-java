package com.dsh.core.session.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.dsh.core.session.event.SessionEvent;
import com.dsh.core.session.event.StepEndEvent;
import com.dsh.core.session.event.StepStartEvent;
import com.dsh.core.session.event.ToolCallEvent;
import com.dsh.core.session.event.ToolResultEvent;
import com.dsh.core.session.event.TurnEndEvent;
import com.dsh.core.session.event.TurnStartEvent;
import com.dsh.core.session.message.ToolResultBlock;
import com.dsh.core.session.store.SessionLogCorruptedException;

/**
 * 崩溃修复测试: closer 合成规则, 已闭合返回空, 只修尾部, 结构损坏拒绝静默修复。
 */
class SessionReplayerTest {

    @Test
    void 开放turn_step与两条未配对call_合成4条closer_顺序正确() {
        var events = List.<SessionEvent>of(
                TurnStartEvent.draft(1),
                StepStartEvent.draft(1, 1),
                ToolCallEvent.draft(1, 1, "call_a", "t1", "{}"),
                ToolCallEvent.draft(1, 1, "call_b", "t2", "{}"));
        var closers = SessionReplayer.synthesizeClosers(events);
        assertThat(closers).hasSize(4);
        // 先是按 seq 序的错误 tool/result
        var r0 = (ToolResultEvent) closers.get(0);
        var r1 = (ToolResultEvent) closers.get(1);
        assertThat(callId(r0)).isEqualTo("call_a");
        assertThat(callId(r1)).isEqualTo("call_b");
        assertThat(r0.data().message().content().get(0))
                .extracting(b -> ((ToolResultBlock) b).isError())
                .isEqualTo(true);
        // 后是 step/end 与 turn/end(interrupted)
        assertThat(closers.get(2)).isEqualTo(StepEndEvent.draft(1, 1));
        var turnEnd = (TurnEndEvent) closers.get(3);
        assertThat(turnEnd.data().reason()).isEqualTo(TurnEndEvent.REASON_INTERRUPTED);
    }

    @Test
    void 完整闭合的日志_closer为空() {
        var events = List.<SessionEvent>of(
                TurnStartEvent.draft(1),
                StepStartEvent.draft(1, 1),
                ToolCallEvent.draft(1, 1, "call_a", "t1", "{}"),
                ToolResultEvent.draft(1, 1, "call_a", "ok", false),
                StepEndEvent.draft(1, 1),
                TurnEndEvent.draft(1, TurnEndEvent.REASON_COMPLETED));
        assertThat(SessionReplayer.synthesizeClosers(events)).isEmpty();
    }

    @Test
    void 只修尾部_已闭合的turn1不动_只补turn2() {
        var events = List.<SessionEvent>of(
                TurnStartEvent.draft(1),
                TurnEndEvent.draft(1, TurnEndEvent.REASON_COMPLETED),
                TurnStartEvent.draft(2),
                StepStartEvent.draft(2, 1),
                ToolCallEvent.draft(2, 1, "call_x", "t1", "{}"));
        var closers = SessionReplayer.synthesizeClosers(events);
        assertThat(closers).hasSize(3); // 1 个 tool/result + step/end + turn/end
        var first = (ToolResultEvent) closers.get(0);
        assertThat(callId(first)).isEqualTo("call_x");
        var turnEnd = (TurnEndEvent) closers.get(2);
        assertThat(turnEnd.data().turn()).isEqualTo(2);
    }

    private static String callId(ToolResultEvent event) {
        return ((ToolResultBlock) event.data().message().content().get(0)).callId();
    }

    @Test
    void 已闭合turn内残留未配对call_判定结构损坏() {
        var events = List.<SessionEvent>of(
                TurnStartEvent.draft(1),
                ToolCallEvent.draft(1, 1, "call_a", "t1", "{}"),
                TurnEndEvent.draft(1, TurnEndEvent.REASON_COMPLETED));
        assertThatThrownBy(() -> SessionReplayer.synthesizeClosers(events))
                .isInstanceOf(SessionLogCorruptedException.class)
                .hasMessageContaining("未配对 tool call");
    }

    @Test
    void tool_call出现在turn之外_判定结构损坏() {
        var events = List.<SessionEvent>of(ToolCallEvent.draft(1, 1, "call_a", "t1", "{}"));
        assertThatThrownBy(() -> SessionReplayer.synthesizeClosers(events))
                .isInstanceOf(SessionLogCorruptedException.class)
                .hasMessageContaining("turn 之外");
    }

    @Test
    void tool_result没有配对的call_判定结构损坏() {
        var events = List.<SessionEvent>of(
                TurnStartEvent.draft(1),
                ToolResultEvent.draft(1, 1, "call_missing", "ok", false));
        assertThatThrownBy(() -> SessionReplayer.synthesizeClosers(events))
                .isInstanceOf(SessionLogCorruptedException.class)
                .hasMessageContaining("没有配对");
    }
}
