package com.dsh.core.session;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.dsh.config.DshProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;

/**
 * 多会话管理器: dsh.session.dir 是会话根目录, 每个会话占一个子目录 <sessionId>/session.jsonl。
 * 会话 id 即子目录名 (UUID), 日志 header 里的 id 与之一致。
 *
 * <p>current() 惰性打开: 进程启动后第一次访问时选取根目录下最近修改的会话;
 * 根目录为空则新建。切换 (switchTo/createNew) 只改变 current 指向, 已打开的会话保持
 * 打开状态直到进程退出 -- 进行中的轮次持有的是 Session 对象引用, 不受切换影响。
 *
 * <p>惰性仍是刻意的: 构造期不碰文件系统, 保证 contextLoads 之类的测试无副作用。
 */
@Component
public class SessionManager {

    /** 会话 id 允许的字符集, 同时挡住路径穿越 (id 会拼进文件路径) */
    private static final String ID_PATTERN = "[A-Za-z0-9_-]+";

    private final DshProperties properties;
    private final ObjectMapper mapper;
    private final Map<String, Session> opened = new HashMap<>();
    private String currentId;

    /** 会话摘要, 列表展示用 */
    public record SessionSummary(String id, long events, long updatedAtMillis) {
    }

    public SessionManager(DshProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    /** 当前会话; 首次调用时选最新或新建 */
    public synchronized Session current() {
        if (currentId == null) {
            currentId = latestId().orElseGet(this::createDir);
        }
        return openedSession(currentId);
    }

    /** 根目录下的全部会话, 按最近修改倒序 (仅统计已有 session.jsonl 的目录); 根目录不存在视为空 */
    public synchronized List<SessionSummary> list() {
        if (!Files.isDirectory(root())) {
            return List.of();
        }
        var summaries = new ArrayList<SessionSummary>();
        try (Stream<Path> dirs = Files.list(root())) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                Path file = dir.resolve(Session.FILE_NAME);
                if (!Files.exists(file)) {
                    continue;
                }
                try {
                    summaries.add(new SessionSummary(dir.getFileName().toString(),
                            Files.lines(file).count() - 1, Files.getLastModifiedTime(file).toMillis()));
                } catch (IOException e) {
                    throw new UncheckedIOException("读取会话日志失败: " + dir, e);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("列出会话目录失败: " + root(), e);
        }
        summaries.sort(Comparator.comparingLong(SessionSummary::updatedAtMillis).reversed());
        return List.copyOf(summaries);
    }

    /** 切换到已有会话; id 不存在或非法时抛 IllegalArgumentException */
    public synchronized Session switchTo(String id) {
        if (id == null || !id.matches(ID_PATTERN) || !Files.isDirectory(root().resolve(id))) {
            throw new IllegalArgumentException("会话不存在: " + id);
        }
        currentId = id;
        return openedSession(id);
    }

    /** 新建一个空白会话并切换过去 */
    public synchronized Session createNew() {
        currentId = createDir();
        return openedSession(currentId);
    }

    /** 当前会话 id (即目录名); 未初始化时会触发惰性打开。
     *  不用 Session.id() 的原因: 历史遗留日志的 header id 可能与目录名不一致, 目录名才是外部标识。 */
    public synchronized String currentId() {
        if (currentId == null) {
            current();
        }
        return currentId;
    }

    private Session openedSession(String id) {
        return opened.computeIfAbsent(id, key -> Session.open(root().resolve(key), key, mapper));
    }

    /** 在根目录下建一个 UUID 子目录, 返回其名字 (即会话 id) */
    private String createDir() {
        String id = UUID.randomUUID().toString();
        try {
            Files.createDirectories(root().resolve(id));
        } catch (IOException e) {
            throw new UncheckedIOException("创建会话目录失败: " + id, e);
        }
        return id;
    }

    /** 最近修改的会话 id; 根目录为空 (无 session.jsonl) 时返回 empty */
    private Optional<String> latestId() {
        return list().stream().findFirst().map(SessionSummary::id);
    }

    private Path root() {
        Path dir = properties.getSession().getDir();
        return dir == null ? Paths.get("./data/sessions") : dir;
    }

    @PreDestroy
    public synchronized void shutdown() throws IOException {
        for (Session session : opened.values()) {
            session.close();
        }
        opened.clear();
    }
}
