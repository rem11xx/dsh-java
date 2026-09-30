package com.dsh.core.session.event;

import java.util.List;

import com.dsh.core.session.message.ContentBlock;
import com.dsh.core.session.message.Role;

/** 用户输入。data 就是消息本身 (扁平, 无 message 包裹), 照抄 dsh。 */
public record UserMessageEvent(String type, long seq, long time, Data data) implements SessionEvent {

    public record Data(Role role, List<ContentBlock> content) {
    }

    public static UserMessageEvent draft(List<ContentBlock> content) {
        return new UserMessageEvent(TYPE_USER_MESSAGE, SEQ_UNASSIGNED, 0L, new Data(Role.USER, content));
    }

    @Override
    public SessionEvent withEnvelope(long seq, long time) {
        return new UserMessageEvent(type(), seq, time, data());
    }
}
