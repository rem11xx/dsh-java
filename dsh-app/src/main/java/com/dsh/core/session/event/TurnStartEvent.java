package com.dsh.core.session.event;

/** 一轮交互 (用户输入到最终回复) 的开始。 */
public record TurnStartEvent(String type, long seq, long time, Data data) implements SessionEvent {

    public record Data(int turn) {
    }

    public static TurnStartEvent draft(int turn) {
        return new TurnStartEvent(TYPE_TURN_START, SEQ_UNASSIGNED, 0L, new Data(turn));
    }

    @Override
    public SessionEvent withEnvelope(long seq, long time) {
        return new TurnStartEvent(type(), seq, time, data());
    }
}
