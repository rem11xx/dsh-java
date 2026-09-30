package com.dsh.core.agent;

import java.util.List;

import com.dsh.config.DshProperties;
import com.dsh.core.llm.LlmAdapter;
import com.dsh.core.llm.LlmRequest;
import com.dsh.core.llm.LlmResponse;
import com.dsh.core.session.Session;
import com.dsh.core.session.SessionManager;
import com.dsh.core.session.event.AssistantMessageEvent;
import com.dsh.core.session.event.SessionEvent;
import com.dsh.core.session.event.StepEndEvent;
import com.dsh.core.session.event.StepStartEvent;
import com.dsh.core.session.event.SystemMessageEvent;
import com.dsh.core.session.event.ToolCallEvent;
import com.dsh.core.session.event.ToolResultEvent;
import com.dsh.core.session.event.TurnEndEvent;
import com.dsh.core.session.event.TurnStartEvent;
import com.dsh.core.session.event.UserMessageEvent;
import com.dsh.core.session.message.Message;
import com.dsh.core.session.message.Role;
import com.dsh.core.session.message.TextBlock;
import com.dsh.core.session.message.ToolCallBlock;
import com.dsh.core.tools.ToolRegistry;
import com.dsh.core.tools.ToolResult;
import org.springframework.context.ApplicationEventPublisher;

/**
 * AgentLoop 状态机, 对应 dsh 的 agent-loop 包, 全项目核心:
 *
 * <pre>
 * turn/start -> user/message ->  [ step/start -> LLM -> assistant/message
 *   -> (tool/call -> tool/result)* -> step/end ]* -> turn/end
 * </pre>
 *
 * <p>三条铁律的落地点:
 * <ul>
 * <li>模型可见即已记录: 每次迭代发给 LLM 的 messages 都从 session.deriveMessages() 现场投影,
 *     loop 不持有任何内存 history, 崩溃后靠重放恢复现场接着跑;</li>
 * <li>事件即扩展点: loop 追加的每个事件同步经 ApplicationEventPublisher 广播,
 *     CLI trace / D6 的 SSE 推送都是纯监听者, loop 代码不用改;</li>
 * <li>换实现不改核心: LLM 与工具只依赖 seam 接口, loop 对 provider/具体工具一无所知。</li>
 * </ul>
 *
 * <p>异常语义: LLM 调用失败直接向上抛 (turn/end 已在 finally 里以 error 闭合, 日志可重放修复);
 * 工具失败由 ToolRegistry 归一化为 isError 结果交还模型, 不终止轮次。
 */
public final class AgentLoop {

    private final SessionManager sessions;
    private final LlmAdapter llm;
    private final ToolRegistry tools;
    private final DshProperties properties;
    private final ApplicationEventPublisher publisher;

    /** 一轮执行的结果: turn 编号 + 结束原因 + 最终面向用户的文本 */
    public record TurnResult(int turn, String reason, String text) {
    }

    public AgentLoop(SessionManager sessions, LlmAdapter llm, ToolRegistry tools,
                     DshProperties properties, ApplicationEventPublisher publisher) {
        this.sessions = sessions;
        this.llm = llm;
        this.tools = tools;
        this.properties = properties;
        this.publisher = publisher;
    }

    /**
     * 执行一轮: 用户输入 -> 工具循环 -> 最终回复。
     * 事件顺序对齐 dsh: turn/start -> system/user 消息 -> step 循环 -> turn/end;
     * turn/end 在 finally 中追加, 任何退出路径 (正常/最大步数/异常) 都保证日志闭合。
     */
    public TurnResult run(String userInput) {
        Session session = sessions.current();
        int turn = session.nextTurn();

        append(session, TurnStartEvent.draft(turn));
        ensureSystemPrompt(session, turn);
        append(session, UserMessageEvent.draft(List.of(TextBlock.of(userInput))));

        // 先按最坏情况初始化: 只有正常路径会改写成 completed, 异常路径保持 error
        String reason = TurnEndEvent.REASON_ERROR;
        String finalText = "";
        try {
            int maxSteps = properties.getAgent().getMaxSteps();
            for (int step = 1; step <= maxSteps; step++) {
                append(session, StepStartEvent.draft(turn, step));
                LlmResponse response = llm.generate(request(session));
                append(session, AssistantMessageEvent.draft(turn, step, response.message()));

                List<ToolCallBlock> calls = toolCallsOf(response.message());
                if (calls.isEmpty()) {
                    // 没有工具调用 = 模型认为本轮结束 (即便 finishReason 异常也无从继续)
                    append(session, StepEndEvent.draft(turn, step));
                    reason = TurnEndEvent.REASON_COMPLETED;
                    finalText = textOf(response.message());
                    return new TurnResult(turn, reason, finalText);
                }
                executeToolCalls(session, turn, step, calls);
                append(session, StepEndEvent.draft(turn, step));
            }
            // 步数耗尽仍未收敛: 强制闭合, 交给上层决定如何提示用户
            reason = TurnEndEvent.REASON_MAX_STEPS;
            return new TurnResult(turn, reason, finalText);
        } finally {
            append(session, TurnEndEvent.draft(turn, reason));
        }
    }

    /** 组装模型请求: 消息从日志现场投影, 工具 schema 由注册表白名单投影 */
    private LlmRequest request(Session session) {
        return new LlmRequest(session.deriveMessages(), tools.schemas());
    }

    /** 系统提示词只落一次 (幂等), 模型可见即已记录: 不落日志就不会进请求 */
    private void ensureSystemPrompt(Session session, int turn) {
        String prompt = properties.getAgent().getSystemPrompt();
        if (prompt == null || prompt.isBlank()) {
            return;
        }
        boolean present = session.events().stream().anyMatch(SystemMessageEvent.class::isInstance);
        if (!present) {
            append(session, SystemMessageEvent.draft(turn, 0, Message.of(Role.SYSTEM, prompt)));
        }
    }

    private void executeToolCalls(Session session, int turn, int step, List<ToolCallBlock> calls) {
        for (var call : calls) {
            // 先留痕再执行: arguments 保留原始串, 解析失败也能从日志看到模型到底说了什么
            append(session, ToolCallEvent.draft(turn, step, call.callId(), call.name(), call.arguments()));
            ToolResult result = tools.execute(call.callId(), call.name(), call.arguments());
            append(session, ToolResultEvent.draft(turn, step, call.callId(), result.content(), result.isError()));
        }
    }

    /** 追加事件 = 落盘 + 广播, 监听者 (CLI trace / SSE) 与 loop 完全解耦 */
    private SessionEvent append(Session session, SessionEvent draft) {
        SessionEvent stamped = session.append(draft);
        publisher.publishEvent(stamped);
        return stamped;
    }

    private static List<ToolCallBlock> toolCallsOf(Message message) {
        return message.content().stream()
                .filter(ToolCallBlock.class::isInstance)
                .map(ToolCallBlock.class::cast)
                .toList();
    }

    private static String textOf(Message message) {
        StringBuilder sb = new StringBuilder();
        for (var block : message.content()) {
            if (block instanceof TextBlock text) {
                sb.append(text.text());
            }
        }
        return sb.toString();
    }
}
