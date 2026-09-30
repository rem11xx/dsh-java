package com.dsh.core.session.replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.dsh.core.session.event.SessionEvent;
import com.dsh.core.session.event.StepEndEvent;
import com.dsh.core.session.event.StepStartEvent;
import com.dsh.core.session.event.ToolCallEvent;
import com.dsh.core.session.event.ToolResultEvent;
import com.dsh.core.session.event.TurnEndEvent;
import com.dsh.core.session.event.TurnStartEvent;
import com.dsh.core.session.message.ToolResultBlock;
import com.dsh.core.session.store.SessionFileStore;
import com.dsh.core.session.store.SessionLogCorruptedException;

/**
 * 启动重放与崩溃修复, 对应 dsh 的 interruptedTurnClosers (packages/core/session/src/repair.ts)。
 *
 * <p>进程中断可能让日志停留在未闭合状态: turn/step 未闭合, 或 tool/call 没有配对的 tool/result。
 * 本类扫描事件流找出这些尾部开放结构, 合成关闭事件 (closer) 经正常 append 追加落盘:
 * 未配对调用 -> tool/result(isError); 开放 step -> step/end; 开放 turn -> turn/end(interrupted)。
 *
 * <p>interrupted 是唯一不由 loop 实时发出的 turn/end reason, 只出现在崩溃修复路径 (PPT 讲解点)。
 */
public final class SessionReplayer {

    private SessionReplayer() {
    }

    /**
     * 打开会话时的入口: 扫描事件流 -> 合成并追加修复事件 -> 返回最终事件列表。
     * 注意调用方必须已处理撕裂尾截断 (SessionFileStore.open 内置)。
     */
    public static List<SessionEvent> loadAndRepair(SessionFileStore store) {
        var events = store.events();
        var closers = synthesizeClosers(events);
        var result = new ArrayList<SessionEvent>(events.size() + closers.size());
        result.addAll(events);
        for (var closer : closers) {
            result.add(store.append(closer));
        }
        return List.copyOf(result);
    }

    /**
     * 纯函数: 给定事件列表, 返回应合成的 closer 草稿列表。
     * 独立可测, 与落盘解耦。
     */
    public static List<SessionEvent> synthesizeClosers(List<SessionEvent> events) {
        var scan = new ScanState();
        for (var event : events) {
            scan.accept(event);
        }
        var closers = new ArrayList<SessionEvent>();
        // 未配对 tool call 按插入序 (即 seq 序) 合成错误结果
        for (var pending : scan.pendingCalls.values()) {
            closers.add(ToolResultEvent.draft(pending.turn(), pending.step(), pending.callId(),
                    "tool call interrupted: session crashed before completion", true));
        }
        if (scan.openStep >= 0) {
            closers.add(StepEndEvent.draft(scan.openStepTurn, scan.openStep));
        }
        if (scan.openTurn >= 0) {
            closers.add(TurnEndEvent.draft(scan.openTurn, TurnEndEvent.REASON_INTERRUPTED));
        }
        return closers;
    }

    /** 单遍扫描状态机: 跟踪开放的 turn/step 与未配对的 tool call */
    private static final class ScanState {

        private int openTurn = -1;
        private int openStepTurn = -1;
        private int openStep = -1;
        private final Map<String, PendingCall> pendingCalls = new LinkedHashMap<>();

        void accept(SessionEvent event) {
            // 注意: 用 instanceof 链而不是 switch 模式匹配, 保持与 IDE language level 17 兼容
            if (event instanceof TurnStartEvent e) {
                if (openTurn >= 0) {
                    throw new SessionLogCorruptedException(
                            "turn " + openTurn + " 未闭合就开启了 turn " + e.data().turn());
                }
                openTurn = e.data().turn();
                openStep = -1;
            } else if (event instanceof TurnEndEvent e) {
                if (e.data().turn() != openTurn) {
                    throw new SessionLogCorruptedException(
                            "turn/end 编号不匹配: 开放的是 " + openTurn + " 结束的是 " + e.data().turn());
                }
                if (!pendingCalls.isEmpty()) {
                    throw new SessionLogCorruptedException(
                            "turn " + openTurn + " 闭合时仍有 " + pendingCalls.size() + " 个未配对 tool call");
                }
                openTurn = -1;
                openStep = -1;
            } else if (event instanceof StepStartEvent e) {
                if (openStep >= 0) {
                    throw new SessionLogCorruptedException(
                            "step " + openStepTurn + "/" + openStep + " 未闭合就开启了新 step");
                }
                openStepTurn = e.data().turn();
                openStep = e.data().step();
            } else if (event instanceof StepEndEvent e) {
                if (e.data().turn() != openStepTurn || e.data().step() != openStep) {
                    throw new SessionLogCorruptedException(
                            "step/end 编号不匹配: 开放的是 " + openStepTurn + "/" + openStep
                                    + " 结束的是 " + e.data().turn() + "/" + e.data().step());
                }
                openStep = -1;
            } else if (event instanceof ToolCallEvent e) {
                if (openTurn < 0) {
                    throw new SessionLogCorruptedException("tool/call 出现在任何 turn 之外: " + e.data().callId());
                }
                pendingCalls.put(e.data().callId(),
                        new PendingCall(e.data().turn(), e.data().step(), e.data().callId()));
            } else if (event instanceof ToolResultEvent e) {
                var callId = firstToolCallId(e);
                if (pendingCalls.remove(callId) == null) {
                    throw new SessionLogCorruptedException("tool/result 没有配对的 tool/call: " + callId);
                }
            }
            // user/system/assistant 消息事件与结构无关, 跳过
        }

        private static String firstToolCallId(ToolResultEvent event) {
            var content = event.data().message().content();
            for (var block : content) {
                if (block instanceof ToolResultBlock r) {
                    return r.callId();
                }
            }
            throw new SessionLogCorruptedException("tool/result 缺少 tool_result 块");
        }
    }

    private record PendingCall(int turn, int step, String callId) {
    }
}
