package com.dsh.web;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.dsh.core.session.event.SessionEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 事件扇出器: 监听 AgentLoop 广播的 SessionEvent, 推给所有已连接的浏览器。
 *
 * <p>与 D5 的 CLI trace 同一模式的第二个消费者 -- 依然零侵入 AgentLoop,
 * "事件即扩展点"原则的 Web 侧证明 (PPT 讲解点)。
 *
 * <p>发送失败的 emitter 视为连接已死, 立即移除, 不影响其他订阅者。
 */
@Component
public class SseEventBroadcaster {

    private final ObjectMapper mapper;
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEventBroadcaster(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 注册一个新的 SSE 订阅连接, 任一退出回调触发时自动注销 */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L); // 0 = 不超时, 演示期间保持长连
        register(emitter);
        return emitter;
    }

    /** 实际注册逻辑, 独立出来便于单测注入 mock emitter */
    void register(SseEmitter emitter) {
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
    }

    /** loop 的每次 append 都会同步走到这里 (发布线程 = 执行轮次的线程) */
    @EventListener
    public void onEvent(SessionEvent event) {
        broadcast(event);
    }

    void broadcast(SessionEvent event) {
        if (emitters.isEmpty()) {
            return;
        }
        String json = toJson(event);
        for (SseEmitter emitter : List.copyOf(emitters)) {
            try {
                emitter.send(SseEmitter.event().name(event.type()).data(json));
            } catch (Exception e) {
                // 连接已断开/超时等: 移除死连接, 其余订阅者不受影响
                emitters.remove(emitter);
            }
        }
    }

    /** 关闭所有订阅连接 (仅测试用, 便于 asyncDispatch 收尾) */
    void completeAll() {
        for (SseEmitter emitter : List.copyOf(emitters)) {
            emitters.remove(emitter);
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // 已关闭的连接重复 complete 属正常
            }
        }
    }

    int subscriberCount() {
        return emitters.size();
    }

    private String toJson(SessionEvent event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            // 事件都是简单 record, 序列化失败只可能是实现 bug, 快速失败
            throw new UncheckedIOException("session 事件序列化失败: " + event.type(), e);
        }
    }
}
