package com.dsh.core.session.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.dsh.core.session.event.SessionEvent;
import com.dsh.core.session.event.TurnStartEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 存储层测试: header 首行, append 与 seq 连续性, 跨进程续写, 损坏检测, 撕裂尾截断。
 */
class SessionFileStoreTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private Path file() {
        return tempDir.resolve("session.jsonl");
    }

    @Test
    void create_写入header首行() throws IOException {
        try (var store = SessionFileStore.create(file(), "s-1", MAPPER)) {
            assertThat(store.header().type()).isEqualTo("session");
            assertThat(store.header().version()).isEqualTo(1);
            assertThat(store.header().id()).isEqualTo("s-1");
        }
        var firstLine = Files.readAllLines(file()).get(0);
        assertThat(firstLine).contains("\"type\":\"session\"").contains("\"id\":\"s-1\"");
    }

    @Test
    void create_拒绝覆盖已有日志() throws IOException {
        try (var store = SessionFileStore.create(file(), "s-1", MAPPER)) {
            store.append(TurnStartEvent.draft(1));
        }
        assertThatThrownBy(() -> SessionFileStore.create(file(), "s-2", MAPPER))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void append_三条事件_seq连续_每行完整JSON() throws IOException {
        try (var store = SessionFileStore.create(file(), "s-1", MAPPER)) {
            for (int i = 0; i < 3; i++) {
                var event = store.append(TurnStartEvent.draft(i + 1));
                assertThat(event.seq()).isEqualTo(i);
            }
        }
        var lines = Files.readAllLines(file());
        assertThat(lines).hasSize(4); // header + 3 events
        assertThat(lines.get(1)).contains("\"type\":\"turn/start\"").contains("\"seq\":0");
        assertThat(lines.get(3)).contains("\"seq\":2");
    }

    @Test
    void 关闭重开_seq从断点续写() throws IOException {
        try (var store = SessionFileStore.create(file(), "s-1", MAPPER)) {
            store.append(TurnStartEvent.draft(1));
        }
        try (var store = SessionFileStore.open(file(), MAPPER)) {
            var event = store.append(TurnStartEvent.draft(2));
            assertThat(event.seq()).isEqualTo(1); // 跨进程连续
            assertThat(store.events()).hasSize(2);
        }
    }

    @Test
    void append_已分配seq的事件被拒绝() throws IOException {
        try (var store = SessionFileStore.create(file(), "s-1", MAPPER)) {
            var stamped = store.append(TurnStartEvent.draft(1));
            assertThatThrownBy(() -> store.append(stamped)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void seq断裂_抛损坏异常() throws IOException {
        try (var store = SessionFileStore.create(file(), "s-1", MAPPER)) {
            store.append(TurnStartEvent.draft(1));
        }
        // 手工在文件中间插入一条 seq=5 的事件
        var lines = Files.readAllLines(file());
        lines.add(1, "{\"type\":\"turn/start\",\"seq\":5,\"time\":1,\"data\":{\"turn\":9}}");
        Files.write(file(), String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> SessionFileStore.open(file(), MAPPER))
                .isInstanceOf(SessionLogCorruptedException.class)
                .hasMessageContaining("seq 断裂");
    }

    @Test
    void 撕裂尾行_打开时截断_继续追加seq正确() throws IOException {
        try (var store = SessionFileStore.create(file(), "s-1", MAPPER)) {
            store.append(TurnStartEvent.draft(1));
        }
        // 追加半行 JSON (无换行终止), 模拟崩溃撕裂
        Files.writeString(file(), "{\"type\":\"turn/start\",\"seq\":1,\"ti", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);
        try (var store = SessionFileStore.open(file(), MAPPER)) {
            assertThat(store.events()).hasSize(1); // 半行被截掉
            var event = store.append(TurnStartEvent.draft(2));
            assertThat(event.seq()).isEqualTo(1); // 从完整前缀续写
        }
        var lines = Files.readAllLines(file());
        assertThat(lines).hasSize(3);
        assertThat(lines.get(2)).contains("\"turn\":2");
    }
}
