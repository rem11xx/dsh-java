package com.dsh.web;

import java.util.List;
import java.util.Map;

import com.dsh.core.agent.AgentLoop;
import com.dsh.core.llm.LlmAdapter;
import com.dsh.core.llm.LlmException;
import com.dsh.core.session.SessionManager;
import com.dsh.core.tools.ToolRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 的 Web 入口: 浏览器发一条用户消息, loop 执行一轮。
 *
 * <p>轮次执行是同步的 (v1 非流式 LLM, 一次请求整个 turn),
 * 过程事件不体现在这个响应里 -- 全部经 SSE 实时推给订阅中的页面。
 *
 * <p>synchronized: v1 单会话, 串行化并发请求避免事件交错写坏日志;
 * 演示页在轮次进行中禁用输入框。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentLoop loop;
    private final SessionManager sessions;
    private final LlmAdapter llm;
    private final ToolRegistry tools;

    public AgentController(AgentLoop loop, SessionManager sessions, LlmAdapter llm, ToolRegistry tools) {
        this.loop = loop;
        this.sessions = sessions;
        this.llm = llm;
        this.tools = tools;
    }

    public record TurnRequest(String text) {
    }

    /** 直接透出 AgentLoop.TurnResult (turn/reason/text) */
    @PostMapping("/turn")
    public synchronized ResponseEntity<?> turn(@RequestBody TurnRequest request) {
        if (request.text() == null || request.text().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "text 不能为空"));
        }
        try {
            return ResponseEntity.ok(loop.run(request.text().strip()));
        } catch (LlmException e) {
            // turn/end(error) 已实时广播, 这里只把失败原因回给发起请求的客户端
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    /** 演示页头部信息: 会话 id (目录名) / provider / 可用工具 */
    @GetMapping("/info")
    public Map<String, Object> info() {
        return Map.of(
                "sessionId", sessions.currentId(),
                "provider", llm.providerName(),
                "tools", tools.names(),
                "events", sessions.current().events().size());
    }

    /** 内置工具 schema 列表, 演示页展示"模型看到了什么" */
    @GetMapping("/tools")
    public List<Map<String, Object>> tools() {
        return tools.schemas().stream()
                .map(schema -> Map.<String, Object>of(
                        "name", schema.name(),
                        "description", schema.description(),
                        "parameters", schema.parameters()))
                .toList();
    }
}
