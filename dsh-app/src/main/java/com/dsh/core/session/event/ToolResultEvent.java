package com.dsh.core.session.event;

import java.util.List;

import com.dsh.core.session.message.Message;
import com.dsh.core.session.message.Role;
import com.dsh.core.session.message.TextBlock;
import com.dsh.core.session.message.ToolResultBlock;

/** 工具执行结果, data.message 是 role=user 且携带 ToolResultBlock 的消息, 照抄 dsh。 */
public record ToolResultEvent(String type, long seq, long time, Data data) implements SessionEvent {

    public record Data(int turn, int step, Message message) {
    }

    public static ToolResultEvent draft(int turn, int step, String callId, String content, boolean isError) {
        var message = new Message(Role.USER,
                List.of(ToolResultBlock.of(callId, List.of(TextBlock.of(content)), isError)));
        return new ToolResultEvent(TYPE_TOOL_RESULT, SEQ_UNASSIGNED, 0L, new Data(turn, step, message));
    }

    @Override
    public SessionEvent withEnvelope(long seq, long time) {
        return new ToolResultEvent(type(), seq, time, data());
    }
}
