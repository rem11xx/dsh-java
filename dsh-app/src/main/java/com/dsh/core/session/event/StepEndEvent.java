package com.dsh.core.session.event;

/** 一个步骤的结束。 */
public record StepEndEvent(String type, long seq, long time, Data data) implements SessionEvent {

    public record Data(int turn, int step) {
    }

    public static StepEndEvent draft(int turn, int step) {
        return new StepEndEvent(TYPE_STEP_END, SEQ_UNASSIGNED, 0L, new Data(turn, step));
    }

    @Override
    public SessionEvent withEnvelope(long seq, long time) {
        return new StepEndEvent(type(), seq, time, data());
    }
}
