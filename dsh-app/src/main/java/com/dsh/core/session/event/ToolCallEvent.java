package com.dsh.core.session.event;

/**
 * 模型发起的工具调用。arguments 是模型产出的原始 JSON 字符串, 不解析。
 * tool/call 本身不投影进模型上下文: 调用请求已在上一步 assistant 消息的 ToolCallBlock 里。
 */
public record ToolCallEvent(String type, long seq, long time, Data data) implements SessionEvent {

    public record Data(int turn, int step, String callId, String name, String arguments) {
    }

    public static ToolCallEvent draft(int turn, int step, String callId, String name, String arguments) {
        return new ToolCallEvent(TYPE_TOOL_CALL, SEQ_UNASSIGNED, 0L, new Data(turn, step, callId, name, arguments));
    }

    @Override
    public SessionEvent withEnvelope(long seq, long time) {
        return new ToolCallEvent(type(), seq, time, data());
    }
}
