package com.dsh.plugin.stub;

import java.util.List;

import com.dsh.core.llm.FinishReason;
import com.dsh.core.llm.LlmAdapter;
import com.dsh.core.llm.LlmRequest;
import com.dsh.core.llm.LlmResponse;
import com.dsh.core.llm.TokenUsage;
import com.dsh.core.session.message.ContentBlock;
import com.dsh.core.session.message.Message;
import com.dsh.core.session.message.Role;
import com.dsh.core.session.message.TextBlock;
import com.dsh.core.session.message.ToolCallBlock;
import com.dsh.core.session.message.ToolResultBlock;

/**
 * 脚本化 LLM 适配器: 完全不联网, 按会话形状走固定剧本 --
 *
 * <ul>
 * <li>最后一条消息不是工具结果 (即新一轮的用户输入) 且工具在册: 返回一次 tool_call
 *     (finishReason=TOOL_CALLS);</li>
 * <li>最后一条消息是工具结果: 引用该结果返回最终文本 (finishReason=STOP)。</li>
 * </ul>
 *
 * <p>按消息形状而不是按调用次数判断, 适配器无状态, 每一轮都能完整走完
 * "用户输入 -> tool_call -> 工具执行 -> 结果回填 -> 最终回复" 的闭环。
 *
 * <p>AgentLoop/SSE/session 日志全套照常工作,
 * 只是 provider 从 DeepSeek 换成了本地剧本 -- 换实现不改核心。
 */
public class StubLlmAdapter implements LlmAdapter {

    private final String toolName;

    public StubLlmAdapter(String toolName) {
        this.toolName = toolName;
    }

    @Override
    public String providerName() {
        return "stub";
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        boolean toolKnown = request.tools().stream().anyMatch(t -> toolName.equals(t.name()));
        boolean awaitingResult = lastMessageHasToolResult(request);
        if (toolKnown && !awaitingResult) {
            var block = ToolCallBlock.of("stub-call-1", toolName, "{}");
            return new LlmResponse(new Message(Role.ASSISTANT, List.of(block)),
                    FinishReason.TOOL_CALLS, new TokenUsage(1, 1, 2));
        }
        return new LlmResponse(new Message(Role.ASSISTANT, List.of(TextBlock.of(finalText(request)))),
                FinishReason.STOP, new TokenUsage(1, 1, 2));
    }

    /**
     * 最后一条消息是否为工具结果 (说明上一条 tool_call 已执行完, 该收尾了)。
     * 只看最后一条而不是整个历史: 上一轮的工具结果会留在历史里,
     * 新一轮用户输入追加后, 最后一条消息又变回纯文本, 剧本可以重新演一遍。
     */
    private static boolean lastMessageHasToolResult(LlmRequest request) {
        List<Message> messages = request.messages();
        if (messages.isEmpty()) {
            return false;
        }
        return messages.get(messages.size() - 1).content().stream().anyMatch(ToolResultBlock.class::isInstance);
    }

    /** 最终回复: 引用最后一个工具结果, 证明 tool_call -> execute -> result -> 再次调用 的闭环真的走通了 */
    private String finalText(LlmRequest request) {
        String result = lastToolResult(request);
        if (result == null) {
            return "[stub] 工具 " + toolName + " 不在可用列表, 直接回复";
        }
        return "[stub] 工具 " + toolName + " 返回: " + result;
    }

    /** 从请求消息里找最后一个工具结果的文本 (消息即日志投影, 适配器天然能看到完整历史) */
    private static String lastToolResult(LlmRequest request) {
        List<Message> messages = request.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            List<ContentBlock> blocks = messages.get(i).content();
            for (int j = blocks.size() - 1; j >= 0; j--) {
                var block = blocks.get(j);
                if (block instanceof ToolResultBlock result) {
                    return result.content().stream()
                            .filter(TextBlock.class::isInstance)
                            .map(TextBlock.class::cast)
                            .map(TextBlock::text)
                            .findFirst()
                            .orElse("(空)");
                }
            }
        }
        return null;
    }
}
