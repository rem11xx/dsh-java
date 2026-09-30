package com.dsh.web;

import java.io.IOException;

import com.dsh.core.session.SessionManager;
import com.dsh.core.session.event.SessionEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 事件流端点。浏览器 (D6 演示页) 订阅后实时收到 loop 广播的全部 SessionEvent。
 *
 * <p>?sinceSeq=N 支持刷新/断线恢复: 先订阅实时流, 再补发 seq &gt; N 的历史事件,
 * 客户端按 seq 去重, 保证不丢也不重 -- 与 session 日志重放同一套 seq 语义。
 */
@RestController
@RequestMapping("/api/events")
public class EventsController {

    private final SseEventBroadcaster broadcaster;
    private final SessionManager sessions;
    private final ObjectMapper mapper;

    public EventsController(SseEventBroadcaster broadcaster, SessionManager sessions, ObjectMapper mapper) {
        this.broadcaster = broadcaster;
        this.sessions = sessions;
        this.mapper = mapper;
    }

    /**
     * SSE 长连接。流程: 先注册到广播器 (此后新事件实时推送),
     * 再把 sinceSeq 之后的历史补发给这条连接, 时序竞争由客户端按 seq 去重兜底。
     */
    @GetMapping("/stream")
    public SseEmitter stream(@RequestParam(defaultValue = "-1") long sinceSeq) throws IOException {
        SseEmitter emitter = broadcaster.subscribe();
        if (sinceSeq >= 0) {
            for (SessionEvent event : sessions.current().events()) {
                if (event.seq() > sinceSeq) {
                    emitter.send(SseEmitter.event().name(event.type())
                            .data(mapper.writeValueAsString(event)));
                }
            }
        }
        return emitter;
    }
}
