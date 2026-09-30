package com.dsh.core.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.dsh.core.session.event.AssistantMessageEvent;
import com.dsh.core.session.event.SessionEvent;
import com.dsh.core.session.event.SystemMessageEvent;
import com.dsh.core.session.event.ToolResultEvent;
import com.dsh.core.session.event.TurnEndEvent;
import com.dsh.core.session.event.TurnStartEvent;
import com.dsh.core.session.event.UserMessageEvent;
import com.dsh.core.session.message.Message;
import com.dsh.core.session.replay.SessionReplayer;
import com.dsh.core.session.store.SessionFileStore;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 会话聚合根: 事件列表 + 物理存储。
 *
 * <p>对应 dsh 的 Session 对象 = 仅追加事件数组 + 派生结果。本类的核心约束:
 * 每次迭代都可从日志独立重建, 禁止只存内存状态 - 发给 LLM 的消息一律走 deriveMessages() 投影,
 * 不另维护内存 history。
 */
public final class Session implements AutoCloseable {

    public static final String FILE_NAME = "session.jsonl";

    private final SessionFileStore store;
    private final List<SessionEvent> events;

    private Session(SessionFileStore store, List<SessionEvent> events) {
        this.store = store;
        this.events = new ArrayList<>(events);
    }

    /** 打开 (或首次创建) 目录下的会话日志, 并执行重放 + 崩溃修复, 日志 header 随机生成 id */
    public static Session open(Path dir, ObjectMapper mapper) {
        return open(dir, UUID.randomUUID().toString(), mapper);
    }

    /** 同上, 但显式指定会话 id (多会话模式下用目录名作 id, 保证 header 与目录一致) */
    public static Session open(Path dir, String sessionId, ObjectMapper mapper) {
        Path file = dir.resolve(FILE_NAME);
        SessionFileStore store = Files.exists(file)
                ? SessionFileStore.open(file, mapper)
                : SessionFileStore.create(file, sessionId, mapper);
        // 重放入口: 合成 interrupted closer 并追加落盘
        var events = SessionReplayer.loadAndRepair(store);
        return new Session(store, events);
    }

    /** 追加事件: 盖章 + 落盘 + 更新内存, 三者在一个锁内完成 */
    public synchronized SessionEvent append(SessionEvent draft) {
        var stamped = store.append(draft);
        events.add(stamped);
        return stamped;
    }

    public synchronized List<SessionEvent> events() {
        return List.copyOf(events);
    }

    /**
     * 从事件投影出模型可见消息, 对应 dsh 的 deriveMessages():
     * user/system/tool-result 直接投影; assistant 内容为空则跳过;
     * tool/call 不投影 (调用已在 assistant 消息的 ToolCallBlock 里); turn/step 结构事件不投影。
     * 这是"模型可见即已记录"原则的落地: 请求消息 = 对日志的投影, 而不是独立的内存 history。
     */
    public List<Message> deriveMessages() {
        List<Message> out = new ArrayList<>();
        for (var event : events) {
            // 注意: 用 instanceof 链而不是 switch 模式匹配, 保持与 IDE language level 17 兼容
            if (event instanceof UserMessageEvent e) {
                out.add(new Message(e.data().role(), e.data().content()));
            } else if (event instanceof SystemMessageEvent e) {
                out.add(e.data().message());
            } else if (event instanceof AssistantMessageEvent e) {
                if (!e.data().message().content().isEmpty()) {
                    out.add(e.data().message());
                }
            } else if (event instanceof ToolResultEvent e) {
                out.add(e.data().message());
            }
            // turn/*, step/*, tool/call: 结构信息, 不投影
        }
        return List.copyOf(out);
    }

    public String id() {
        return store.header().id();
    }

    /** 从日志推算下一个 turn 编号 (已有最大值 + 1), turn 编号不依赖内存状态 */
    public synchronized int nextTurn() {
        int max = 0;
        for (var event : events) {
            if (event instanceof TurnStartEvent e) {
                max = Math.max(max, e.data().turn());
            } else if (event instanceof TurnEndEvent e) {
                max = Math.max(max, e.data().turn());
            }
        }
        return max + 1;
    }

    /**
     * 显式触发崩溃修复: 对当前事件流合成 interrupted closer 并经正常 append 落盘。
     * 真实场景下进程重启后 Session.open 会自动走这一步, 此方法供演示端点使用。
     */
    public synchronized List<SessionEvent> repair() {
        var closers = SessionReplayer.synthesizeClosers(events);
        var appended = new ArrayList<SessionEvent>(closers.size());
        for (var closer : closers) {
            appended.add(append(closer));
        }
        return appended;
    }

    @Override
    public void close() throws IOException {
        store.close();
    }
}
