package com.dsh.core.agent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import com.dsh.core.llm.LlmAdapter;
import com.dsh.core.llm.LlmException;
import com.dsh.core.session.SessionManager;
import com.dsh.core.session.event.AssistantMessageEvent;
import com.dsh.core.session.event.SessionEvent;
import com.dsh.core.session.event.ToolCallEvent;
import com.dsh.core.session.event.ToolResultEvent;
import com.dsh.core.session.event.TurnEndEvent;
import com.dsh.core.session.event.TurnStartEvent;
import com.dsh.core.session.message.TextBlock;
import com.dsh.core.tools.ToolRegistry;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * D5 命令行端到端入口: --dsh.cli.enabled=true 时启动 REPL。
 * 轮次 trace 靠 @EventListener
 * 监听 loop 广播的 SessionEvent 实现
 */
@Component
@ConditionalOnProperty(name = "dsh.cli.enabled", havingValue = "true")
public class AgentCliRunner implements CommandLineRunner {

    private final AgentLoop loop;
    private final SessionManager sessions;
    private final LlmAdapter llm;
    private final ToolRegistry tools;
    private final ApplicationContext context;

    public AgentCliRunner(AgentLoop loop, SessionManager sessions, LlmAdapter llm, ToolRegistry tools,
            ApplicationContext context) {
        this.loop = loop;
        this.sessions = sessions;
        this.llm = llm;
        this.tools = tools;
        this.context = context;
    }

    @Override
    public void run(String... args) throws Exception {
        System.out.println("dsh-java CLI  (输入 exit 退出)");
        System.out.println("session: " + sessions.current().id()
                + "  provider: " + llm.providerName()
                + "  tools: " + tools.names());
        System.out.println();

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            System.out.print("dsh> ");
            System.out.flush();
            String line = reader.readLine();
            if (line == null) {
                break;
            }
            line = line.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.equals("exit") || line.equals("quit")) {
                break;
            }
            try {
                var result = loop.run(line);
                // 正常回复已由事件 trace 实时打印 (assistant/message), 这里只兜底提示未收敛的情况
                if (result.text().isEmpty()) {
                    System.out.println("assistant: (未收敛, 原因: " + result.reason() + ")");
                }
            } catch (LlmException e) {
                System.out.println("[llm 错误] " + e.getMessage());
            } catch (RuntimeException e) {
                System.out.println("[错误] " + e.getMessage());
            }
            System.out.println();
        }

        // REPL 结束后必须显式退出: Tomcat 等非守护线程会让 JVM 在 main 返回后继续存活
        System.out.println("bye");
        System.exit(SpringApplication.exit(context));
    }

    /** 轮次 trace: 只挑用户关心的几类事件, 其余 (step/user-message) 略过降噪 */
    @EventListener
    public void onEvent(SessionEvent event) {
        // 注意: 用 instanceof 链而不是 switch 模式匹配, 保持与 IDE language level 17 兼容
        if (event instanceof TurnStartEvent e) {
            System.out.println("--- turn " + e.data().turn() + " ---");
        } else if (event instanceof TurnEndEvent e) {
            System.out.println("--- turn " + e.data().turn() + " 结束 (" + e.data().reason() + ") ---");
        } else if (event instanceof AssistantMessageEvent e) {
            for (var block : e.data().message().content()) {
                if (block instanceof TextBlock text && !text.text().isBlank()) {
                    System.out.println("assistant: " + text.text());
                }
            }
        } else if (event instanceof ToolCallEvent e) {
            System.out.println("-> 工具 " + e.data().name() + " " + e.data().arguments());
        } else if (event instanceof ToolResultEvent e) {
            System.out.println("<- " + preview(e));
        }
    }

    private static String preview(ToolResultEvent e) {
        // 结果消息 = 单个 ToolResultBlock, 其内层才是模型可见文本
        if (e.data().message().content().get(0) instanceof com.dsh.core.session.message.ToolResultBlock block) {
            String text = block.content().stream()
                    .filter(TextBlock.class::isInstance)
                    .map(TextBlock.class::cast)
                    .map(TextBlock::text)
                    .findFirst()
                    .orElse("(空)");
            if (text.length() > 120) {
                text = text.substring(0, 120) + "...(截断)";
            }
            return "工具结果" + (block.isError() ? " (error): " : ": ") + text;
        }
        return "工具结果: (未知形状)";
    }
}
