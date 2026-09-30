package com.dsh.core.session.event;

/** 一个步骤 = 一次 LLM 请求 + 其工具执行, 的开始。一次 turn 内可有多步。 */
public record StepStartEvent(String type, long seq, long time, Data data) implements SessionEvent {

    public record Data(int turn, int step) {
    }

    public static StepStartEvent draft(int turn, int step) {
        return new StepStartEvent(TYPE_STEP_START, SEQ_UNASSIGNED, 0L, new Data(turn, step));
    }

    @Override
    public SessionEvent withEnvelope(long seq, long time) {
        return new StepStartEvent(type(), seq, time, data());
    }
}
