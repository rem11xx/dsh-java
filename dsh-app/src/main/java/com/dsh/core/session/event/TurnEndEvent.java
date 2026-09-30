package com.dsh.core.session.event;

/**
 * 一轮交互的结束。reason 对应 dsh 的 TurnEndReason, v1 取值:
 * completed (正常完成) / max_steps (步数耗尽强制闭合) / error (LLM 调用失败, loop 实时发出)
 * / interrupted (仅由崩溃修复路径合成, loop 实时永远不会发出, PPT 讲解点)。
 */
public record TurnEndEvent(String type, long seq, long time, Data data) implements SessionEvent {

    public static final String REASON_COMPLETED = "completed";
    public static final String REASON_MAX_STEPS = "max_steps";
    public static final String REASON_ERROR = "error";
    public static final String REASON_INTERRUPTED = "interrupted";

    public record Data(int turn, String reason) {
    }

    public static TurnEndEvent draft(int turn, String reason) {
        return new TurnEndEvent(TYPE_TURN_END, SEQ_UNASSIGNED, 0L, new Data(turn, reason));
    }

    @Override
    public SessionEvent withEnvelope(long seq, long time) {
        return new TurnEndEvent(type(), seq, time, data());
    }
}
