package com.dsh.web;

import com.dsh.core.session.event.SessionEvent;
import com.dsh.core.session.event.TurnStartEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * SSE 扇出器单测: 注册/推送/死连接清理。
 * 用 mock emitter 验证交互, 不起 HTTP 服务。
 */
class SseEventBroadcasterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SseEventBroadcaster broadcaster = new SseEventBroadcaster(mapper);

    @Test
    void 事件应推给所有已注册的订阅者() throws Exception {
        SseEmitter a = mock(SseEmitter.class);
        SseEmitter b = mock(SseEmitter.class);
        broadcaster.register(a);
        broadcaster.register(b);

        broadcaster.onEvent(TurnStartEvent.draft(1));

        verify(a).send(any(SseEmitter.SseEventBuilder.class));
        verify(b).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(broadcaster.subscriberCount()).isEqualTo(2);
    }

    @Test
    void 无订阅者时广播应是空操作() {
        // 主要保证这条路径不抛异常 (序列化都不该发生)
        broadcaster.onEvent(TurnStartEvent.draft(1));
        assertThat(broadcaster.subscriberCount()).isZero();
    }

    @Test
    void 发送失败的死连接应被移除且不影响其他订阅者() throws Exception {
        SseEmitter dead = mock(SseEmitter.class);
        SseEmitter alive = mock(SseEmitter.class);
        doThrow(new IllegalStateException("连接已断开")).when(dead).send(any(SseEmitter.SseEventBuilder.class));
        broadcaster.register(dead);
        broadcaster.register(alive);

        broadcaster.onEvent(TurnStartEvent.draft(1));
        assertThat(broadcaster.subscriberCount()).isEqualTo(1);

        // 第二次广播不再碰死连接, 存活者继续收到
        broadcaster.onEvent(TurnStartEvent.draft(2));
        verify(alive, org.mockito.Mockito.times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(dead, org.mockito.Mockito.times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void 序列化应产出带type信封的事件JSON() throws Exception {
        // 事件多态分派依赖 @JsonTypeInfo, 这里确认序列化结果含 type 字段
        SessionEvent event = TurnStartEvent.draft(7);
        String json = mapper.writeValueAsString(event);
        assertThat(json).contains("\"type\":\"turn/start\"");
        assertThat(json).contains("\"turn\":7");
    }

    @Test
    void completeAll应关闭全部连接() {
        SseEmitter a = mock(SseEmitter.class);
        SseEmitter b = mock(SseEmitter.class);
        broadcaster.register(a);
        broadcaster.register(b);

        broadcaster.completeAll();

        verify(a).complete();
        verify(b).complete();
        assertThat(broadcaster.subscriberCount()).isZero();
    }
}
