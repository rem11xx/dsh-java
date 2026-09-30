package com.dsh.core.session.event;

import com.dsh.core.session.message.Message;

/** 系统提示词提交, 模型可见事件里的第 0 号节点。 */
public record SystemMessageEvent(String type, long seq, long time, Data data) implements SessionEvent {

    public record Data(int turn, int step, Message message) {
    }

    public static SystemMessageEvent draft(int turn, int step, Message message) {
        return new SystemMessageEvent(TYPE_SYSTEM_MESSAGE, SEQ_UNASSIGNED, 0L, new Data(turn, step, message));
    }

    @Override
    public SessionEvent withEnvelope(long seq, long time) {
        return new SystemMessageEvent(type(), seq, time, data());
    }
}
