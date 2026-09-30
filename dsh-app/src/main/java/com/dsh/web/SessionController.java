package com.dsh.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.dsh.core.session.Session;
import com.dsh.core.session.SessionManager;
import com.dsh.core.session.event.AssistantMessageEvent;
import com.dsh.core.session.event.SessionEvent;
import com.dsh.core.session.event.StepEndEvent;
import com.dsh.core.session.event.StepStartEvent;
import com.dsh.core.session.event.ToolCallEvent;
import com.dsh.core.session.event.ToolResultEvent;
import com.dsh.core.session.event.TurnEndEvent;
import com.dsh.core.session.event.TurnStartEvent;
import com.dsh.core.session.event.UserMessageEvent;
import com.dsh.core.session.message.Message;
import com.dsh.core.session.message.TextBlock;
import com.dsh.core.session.message.ToolCallBlock;

/**
 * D2 演示端点 (薄壳): 一屏讲完事件流。
 * 亮点路径: demo-turn 看完整轮次 -> simulate-crash 制造未闭合日志 -> repair 看合成 closer -> 重启自动修复。
 * 多会话: list/switch/new 管理会话, 其余端点一律作用于当前会话。
 */
@RestController
@RequestMapping("/api/session")
public class SessionController {

    private final SessionManager sessions;

    public SessionController(SessionManager sessions) {
        this.sessions = sessions;
    }

    /** 追加一条用户消息事件 */
    public record UserMessageRequest(String text) {
    }

    /** 会话列表 (按最近修改倒序) */
    @GetMapping("/list")
    public List<SessionManager.SessionSummary> list() {
        return sessions.list();
    }

    /** 切换当前会话, 返回切换后的会话 id (目录名) */
    @PostMapping("/switch/{id}")
    public Map<String, Object> switchTo(@PathVariable String id) {
        try {
            sessions.switchTo(id);
            return Map.of("sessionId", sessions.currentId());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** 新建空白会话并切换过去 */
    @PostMapping("/new")
    public Map<String, Object> createNew() {
        sessions.createNew();
        return Map.of("sessionId", sessions.currentId());
    }

    @PostMapping("/user-message")
    public SessionEvent userMessage(@RequestBody UserMessageRequest request) {
        return session().append(UserMessageEvent.draft(List.of(TextBlock.of(request.text()))));
    }

    /** 脚本化追加一个完整合法的 turn, 事件形状与 dsh 快照对照 */
    @PostMapping("/demo-turn")
    public List<SessionEvent> demoTurn() {
        var session = session();
        int turn = session.nextTurn();
        int step = 1;
        String callId = "call_" + UUID.randomUUID().toString().substring(0, 8);
        return List.of(
                session.append(TurnStartEvent.draft(turn)),
                session.append(StepStartEvent.draft(turn, step)),
                session.append(AssistantMessageEvent.draft(turn, step,
                        new Message(com.dsh.core.session.message.Role.ASSISTANT,
                                List.of(ToolCallBlock.of(callId, "get_weather", "{\"city\":\"beijing\"}"))))),
                session.append(ToolCallEvent.draft(turn, step, callId, "get_weather", "{\"city\":\"beijing\"}")),
                session.append(ToolResultEvent.draft(turn, step, callId, "晴, 25 度", false)),
                session.append(StepEndEvent.draft(turn, step)),
                session.append(TurnEndEvent.draft(turn, TurnEndEvent.REASON_COMPLETED)));
    }

    /** 模拟崩溃现场: 追加 turn/start + step/start + tool/call 后故意不闭合 */
    @PostMapping("/simulate-crash")
    public List<SessionEvent> simulateCrash() {
        var session = session();
        int turn = session.nextTurn();
        return List.of(
                session.append(TurnStartEvent.draft(turn)),
                session.append(StepStartEvent.draft(turn, 1)),
                session.append(ToolCallEvent.draft(turn, 1, "call_crash", "run_query", "{}")));
        // 故意不追加 step/end 和 turn/end, 模拟进程在此中断
    }

    /** 显式触发崩溃修复 (真实场景在重启后的 Session.open 自动执行) */
    @PostMapping("/repair")
    public List<SessionEvent> repair() {
        return session().repair();
    }

    @GetMapping("/events")
    public List<SessionEvent> events() {
        return session().events();
    }

    /** 模型可见消息投影, 即下次发给 LLM 的 messages */
    @GetMapping("/messages")
    public List<Message> messages() {
        return session().deriveMessages();
    }

    private Session session() {
        return sessions.current();
    }
}
