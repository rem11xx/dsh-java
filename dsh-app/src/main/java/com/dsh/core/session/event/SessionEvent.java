package com.dsh.core.session.event;

import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 会话事件密封接口, 对应 dsh 的 SessionEventMap (packages/core/session/src/types.ts)。
 *
 * <p>事件信封统一为 {type, seq, time, data}, 与 dsh JSONL 格式逐字段同形:
 * seq 由存储层在 append 时分配并校验连续性; time 为 epoch 毫秒。
 *
 * <p>Jackson 通过 type 字段的 EXISTING_PROPERTY 多态分派, 反序列化时该字段同时绑定进 record 组件。
 * 注意: @JsonSubTypes 列表必须与 permits 列表人工保持同步, 有反射测试兜底。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = TurnStartEvent.class, name = SessionEvent.TYPE_TURN_START),
        @JsonSubTypes.Type(value = TurnEndEvent.class, name = SessionEvent.TYPE_TURN_END),
        @JsonSubTypes.Type(value = StepStartEvent.class, name = SessionEvent.TYPE_STEP_START),
        @JsonSubTypes.Type(value = StepEndEvent.class, name = SessionEvent.TYPE_STEP_END),
        @JsonSubTypes.Type(value = UserMessageEvent.class, name = SessionEvent.TYPE_USER_MESSAGE),
        @JsonSubTypes.Type(value = SystemMessageEvent.class, name = SessionEvent.TYPE_SYSTEM_MESSAGE),
        @JsonSubTypes.Type(value = AssistantMessageEvent.class, name = SessionEvent.TYPE_ASSISTANT_MESSAGE),
        @JsonSubTypes.Type(value = ToolCallEvent.class, name = SessionEvent.TYPE_TOOL_CALL),
        @JsonSubTypes.Type(value = ToolResultEvent.class, name = SessionEvent.TYPE_TOOL_RESULT),
})
@JsonIgnoreProperties(ignoreUnknown = true)
public sealed interface SessionEvent permits
        TurnStartEvent, TurnEndEvent, StepStartEvent, StepEndEvent,
        UserMessageEvent, SystemMessageEvent, AssistantMessageEvent,
        ToolCallEvent, ToolResultEvent {

    // 事件类型字符串, 与 dsh 的 kebab-case 对齐
    String TYPE_TURN_START = "turn/start";
    String TYPE_TURN_END = "turn/end";
    String TYPE_STEP_START = "step/start";
    String TYPE_STEP_END = "step/end";
    String TYPE_USER_MESSAGE = "user/message";
    String TYPE_SYSTEM_MESSAGE = "system/message";
    String TYPE_ASSISTANT_MESSAGE = "assistant/message";
    String TYPE_TOOL_CALL = "tool/call";
    String TYPE_TOOL_RESULT = "tool/result";

    /** 草稿事件的未分配 seq 标记 */
    long SEQ_UNASSIGNED = -1;

    /**
     * 模型可见 (surface) 事件类型集合, 对应 dsh 的 SurfaceEventType。
     * deriveMessages() 只投影这四类事件; turn/step/tool-call 等结构事件只落日志不进模型上下文。
     */
    Set<String> MESSAGE_EVENT_TYPES = Set.of(
            TYPE_SYSTEM_MESSAGE, TYPE_USER_MESSAGE, TYPE_ASSISTANT_MESSAGE, TYPE_TOOL_RESULT);

    String type();

    long seq();

    long time();

    /** 落账盖章: 把草稿复制成带 seq/time 的新事件, 由存储层在 append 时调用 */
    SessionEvent withEnvelope(long seq, long time);
}
