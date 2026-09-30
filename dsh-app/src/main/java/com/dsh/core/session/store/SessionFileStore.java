package com.dsh.core.session.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.dsh.core.session.event.SessionEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 会话日志物理存储: JSONL 追加写 + 每条 fsync + 全量扫描 + 撕裂尾截断。
 *
 * <p>对照 dsh 的 session-persistence-jsonl 包: dsh 由 checkpoint policy 决定持久化时机并有跨进程 lease;
 * v1 单进程单会话单写者 (AgentLoop 是唯一写方), synchronized 足够, 每条强制落盘。
 *
 * <p>文件布局: 首行 SessionHeader, 之后每个事件一行 (type/seq/time/data), 首行不占 seq, 事件从 0 起编号。
 */
public final class SessionFileStore implements AutoCloseable {

    private final Path file;
    private final ObjectMapper mapper;
    private final FileChannel channel;
    private final SessionHeader header;
    private final List<SessionEvent> events;

    private SessionFileStore(Path file, ObjectMapper mapper, FileChannel channel, SessionHeader header,
            List<SessionEvent> events) {
        this.file = file;
        this.mapper = mapper;
        this.channel = channel;
        this.header = header;
        this.events = new ArrayList<>(events);
    }

    /** 新建会话日志: 目录不存在则创建, 原子写入 header 首行, 然后按已有文件打开 */
    public static SessionFileStore create(Path file, String sessionId, ObjectMapper mapper) {
        if (Files.exists(file)) {
            throw new IllegalStateException("会话日志已存在, 拒绝覆盖: " + file);
        }
        try {
            Files.createDirectories(file.getParent());
            var header = new SessionHeader(SessionHeader.TYPE_SESSION, SessionHeader.VERSION,
                    sessionId, System.currentTimeMillis());
            // 临时文件 + 原子移动, 对应 dsh 首次物化的 link() 发布
            var tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ch.write(ByteBuffer.wrap((toJson(mapper, header) + "\n").getBytes(StandardCharsets.UTF_8)));
                ch.force(true);
            }
            Files.move(tmp, file);
            return open(file, mapper);
        } catch (IOException e) {
            throw new IllegalStateException("创建会话日志失败: " + file, e);
        }
    }

    /** 打开已有会话日志: 截断撕裂尾行, 校验 header 与 seq 连续性 */
    public static SessionFileStore open(Path file, ObjectMapper mapper) {
        if (!Files.exists(file)) {
            throw new IllegalStateException("会话日志不存在: " + file);
        }
        FileChannel channel = null;
        try {
            byte[] bytes = Files.readAllBytes(file);
            int completeBytes = lastNewline(bytes) + 1;
            if (completeBytes != bytes.length) {
                // 上次进程中断留下半行 JSON, 截掉, 后续 append 从新的 EOF 开始
                try (FileChannel trunc = FileChannel.open(file, StandardOpenOption.WRITE)) {
                    trunc.truncate(completeBytes);
                    trunc.force(true);
                }
            }
            var header = parseHeader(mapper, new String(bytes, 0, completeBytes, StandardCharsets.UTF_8), file);
            var events = parseEvents(mapper, header, completeBytes == 0 ? "" : new String(
                    bytes, firstNewline(bytes) + 1, completeBytes - firstNewline(bytes) - 1, StandardCharsets.UTF_8),
                    file);
            channel = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            return new SessionFileStore(file, mapper, channel, header, events);
        } catch (IOException e) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
            }
            throw new IllegalStateException("打开会话日志失败: " + file, e);
        }
    }

    /**
     * 追加一条事件: 盖章 (分配 seq/time) -> 序列化一行 -> write -> force -> 更新内存。
     * 内存列表与文件在同一把锁内更新, 保证"模型可见即已记录"。
     */
    public synchronized SessionEvent append(SessionEvent draft) {
        if (draft.seq() != SessionEvent.SEQ_UNASSIGNED) {
            throw new IllegalArgumentException("事件 seq 必须未分配, 得到: " + draft.seq());
        }
        if (SessionHeader.TYPE_SESSION.equals(draft.type())) {
            throw new IllegalArgumentException("type=session 是 header 保留字");
        }
        var stamped = draft.withEnvelope(events.size(), System.currentTimeMillis());
        try {
            channel.write(ByteBuffer.wrap((toJson(mapper, stamped) + "\n").getBytes(StandardCharsets.UTF_8)));
            channel.force(true);
        } catch (IOException e) {
            throw new IllegalStateException("追加事件失败 (seq=" + stamped.seq() + ")", e);
        }
        events.add(stamped);
        return stamped;
    }

    public synchronized List<SessionEvent> events() {
        return List.copyOf(events);
    }

    public SessionHeader header() {
        return header;
    }

    // ---------- 解析 ----------

    private static SessionHeader parseHeader(ObjectMapper mapper, String text, Path file) {
        var lines = completeLines(text);
        if (lines.isEmpty()) {
            throw new SessionLogCorruptedException("空日志文件: " + file);
        }
        try {
            var header = mapper.readValue(lines.get(0), SessionHeader.class);
            if (!SessionHeader.TYPE_SESSION.equals(header.type())) {
                throw new SessionLogCorruptedException("首行不是 session header: " + lines.get(0));
            }
            if (header.version() != SessionHeader.VERSION) {
                throw new SessionLogCorruptedException("不支持的日志版本: " + header.version());
            }
            return header;
        } catch (IOException e) {
            throw new SessionLogCorruptedException("header 解析失败: " + lines.get(0), e);
        }
    }

    private static List<SessionEvent> parseEvents(ObjectMapper mapper, SessionHeader header, String body, Path file) {
        var lines = completeLines(body);
        var events = new ArrayList<SessionEvent>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            try {
                var event = mapper.readValue(line, SessionEvent.class);
                if (event.seq() != i) {
                    throw new SessionLogCorruptedException(
                            "seq 断裂: 期望 " + i + " 实际 " + event.seq() + " (第 " + (i + 1) + " 个事件)");
                }
                events.add(event);
            } catch (IOException e) {
                throw new SessionLogCorruptedException("事件解析失败 (第 " + (i + 1) + " 个事件): " + line, e);
            }
        }
        return events;
    }

    /** 按行切分, 去掉结尾空段, 行尾兼容 \r */
    private static List<String> completeLines(String text) {
        if (text.isEmpty()) {
            return List.of();
        }
        var lines = Arrays.asList(text.split("\n", -1));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines = lines.subList(0, lines.size() - 1);
        }
        var out = new ArrayList<String>(lines.size());
        for (var line : lines) {
            out.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
        }
        return out;
    }

    private static int firstNewline(byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static int lastNewline(byte[] bytes) {
        for (int i = bytes.length - 1; i >= 0; i--) {
            if (bytes[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static String toJson(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        channel.close();
    }
}
