package com.dsh.core.session.event;

import com.dsh.core.session.message.Message;

/**
 * 模型回复。省略 dsh 的 stream/usage/interrupted 字段
 * 注意: 内容为空的 assistant 消息在 deriveMessages() 投影时跳过, 但事件仍要落日志 (模型可见即已记录)。
 */
public record AssistantMessageEvent(String type, long seq, long time, Data data) implements SessionEvent {

    public record Data(int turn, int step, Message message) {
    }

    public static AssistantMessageEvent draft(int turn, int step, Message message) {
        return new AssistantMessageEvent(TYPE_ASSISTANT_MESSAGE, SEQ_UNASSIGNED, 0L, new Data(turn, step, message));
    }

    @Override
    public SessionEvent withEnvelope(long seq, long time) {
        return new AssistantMessageEvent(type(), seq, time, data());
    }
}
