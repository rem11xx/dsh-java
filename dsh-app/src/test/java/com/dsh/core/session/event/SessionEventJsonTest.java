package com.dsh.core.session.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.dsh.core.session.message.Message;
import com.dsh.core.session.message.Role;
import com.dsh.core.session.message.TextBlock;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;

/**
 * 事件 JSON 序列化测试: roundtrip, dsh 形状分派, 未知字段忽略, permits 与注解同步。
 */
class SessionEventJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private List<SessionEvent> allDrafts() {
        return List.of(
                TurnStartEvent.draft(1),
                TurnEndEvent.draft(1, TurnEndEvent.REASON_COMPLETED),
                StepStartEvent.draft(1, 1),
                StepEndEvent.draft(1, 1),
                UserMessageEvent.draft(List.of(TextBlock.of("你好"))),
                SystemMessageEvent.draft(1, 1, Message.of(Role.SYSTEM, "系统提示词")),
                AssistantMessageEvent.draft(1, 1, Message.of(Role.ASSISTANT, "回复")),
                ToolCallEvent.draft(1, 1, "call_1", "echo", "{\"x\":1}"),
                ToolResultEvent.draft(1, 1, "call_1", "结果", false));
    }

    @Test
    void 全部9种事件_序列化再反序列化_保持相等() throws Exception {
        for (SessionEvent draft : allDrafts()) {
            var stamped = draft.withEnvelope(7, 1234567890L);
            var json = mapper.writeValueAsString(stamped);
            var back = mapper.readValue(json, SessionEvent.class);
            assertThat(back).isEqualTo(stamped);
            assertThat(back.type()).isEqualTo(draft.type());
            assertThat(back.seq()).isEqualTo(7);
            assertThat(back.time()).isEqualTo(1234567890L);
        }
    }

    @Test
    void 序列化形状与dsh同形_type_seq_time_data顺序() throws Exception {
        var json = mapper.writeValueAsString(TurnStartEvent.draft(1).withEnvelope(0, 1730000000000L));
        // 反序列化到 Map 校验信封字段
        var tree = mapper.readTree(json);
        assertThat(tree.get("type").asText()).isEqualTo("turn/start");
        assertThat(tree.get("seq").asLong()).isEqualTo(0);
        assertThat(tree.get("time").asLong()).isEqualTo(1730000000000L);
        assertThat(tree.get("data").get("turn").asInt()).isEqualTo(1);
    }

    @Test
    void 手写dsh形状JSON_按type分派到正确子类型() throws Exception {
        var json = "{\"type\":\"tool/call\",\"seq\":5,\"time\":1730000000005,"
                + "\"data\":{\"turn\":1,\"step\":1,\"callId\":\"call_9\",\"name\":\"echo\",\"arguments\":\"{}\"}}";
        var event = mapper.readValue(json, SessionEvent.class);
        assertThat(event).isInstanceOf(ToolCallEvent.class);
        assertThat(((ToolCallEvent) event).data().arguments()).isEqualTo("{}");
    }

    @Test
    void 未知字段被忽略_兼容dsh扩展字段() throws Exception {
        var json = "{\"type\":\"user/message\",\"seq\":0,\"time\":1,\"surfaceOp\":\"append\","
                + "\"data\":{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}";
        var event = mapper.readValue(json, SessionEvent.class);
        assertThat(event).isInstanceOf(UserMessageEvent.class);
    }

    @Test
    void 缺少type字段_反序列化报错() {
        assertThatThrownBy(() -> mapper.readValue("{\"seq\":0,\"time\":1,\"data\":{}}", SessionEvent.class))
                .isInstanceOf(InvalidTypeIdException.class);
    }

    @Test
    void permits列表与JsonSubTypes注解一一对应() throws Exception {
        var subTypes = SessionEvent.class.getAnnotation(JsonSubTypes.class).value();
        var permitted = SessionEvent.class.getPermittedSubclasses();
        assertThat(subTypes).hasSize(permitted.length);
        for (int i = 0; i < permitted.length; i++) {
            assertThat(subTypes[i].value()).isEqualTo(permitted[i]);
        }
        // 每个 name 等于对应事件类的 TYPE_* 常量
        for (var subType : subTypes) {
            var field = subType.value().getField("TYPE_" + constantSuffix(subType.name()));
            assertThat(field).isNotNull();
            assertThat((String) field.get(null)).isEqualTo(subType.name());
        }
    }

    @Test
    void 事件类型常量与MESSAGE_EVENT_TYPES一致() {
        // 模型可见事件恰好是 4 种消息事件
        assertThat(SessionEvent.MESSAGE_EVENT_TYPES).containsExactlyInAnyOrder(
                SessionEvent.TYPE_SYSTEM_MESSAGE, SessionEvent.TYPE_USER_MESSAGE,
                SessionEvent.TYPE_ASSISTANT_MESSAGE, SessionEvent.TYPE_TOOL_RESULT);
    }

    @Test
    void 事件record都是不可变类型() {
        for (var permitted : SessionEvent.class.getPermittedSubclasses()) {
            assertThat(Modifier.isFinal(permitted.getModifiers())).isTrue();
        }
    }

    private String constantSuffix(String typeName) {
        // "user/message" -> "USER_MESSAGE"
        var sb = new StringBuilder();
        for (char c : typeName.toCharArray()) {
            if (c == '/') {
                sb.append('_');
            } else if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toUpperCase(c));
            }
        }
        return sb.toString();
    }
}
