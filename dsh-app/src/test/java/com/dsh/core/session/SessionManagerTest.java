package com.dsh.core.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.dsh.config.DshProperties;
import com.dsh.core.session.event.UserMessageEvent;
import com.dsh.core.session.message.TextBlock;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 多会话管理器: list/switch/create, 惰性打开, 跨实例重开 (重启恢复)。
 */
class SessionManagerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private SessionManager manager() {
        var props = new DshProperties();
        props.getSession().setDir(tempDir);
        return new SessionManager(props, MAPPER);
    }

    @Test
    void 空根目录_current自动新建会话() {
        var sessions = manager();
        var session = sessions.current();
        assertThat(session.id()).matches("[a-f0-9-]{36}");
        // 日志文件已落盘, header id 与目录名一致
        assertThat(tempDir.resolve(session.id()).resolve(Session.FILE_NAME)).exists();
    }

    @Test
    void 切换后各会话事件互不串台() {
        var sessions = manager();
        var first = sessions.current();
        first.append(UserMessageEvent.draft(List.of(TextBlock.of("第一条"))));

        var second = sessions.createNew();
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.events()).isEmpty();

        second.append(UserMessageEvent.draft(List.of(TextBlock.of("第二条"))));

        sessions.switchTo(first.id());
        assertThat(sessions.current().id()).isEqualTo(first.id());
        assertThat(sessions.current().events()).hasSize(1);
        assertThat(((UserMessageEvent) sessions.current().events().get(0)).data().content())
                .isEqualTo(List.of(TextBlock.of("第一条")));

        sessions.switchTo(second.id());
        assertThat(sessions.current().events()).hasSize(1);
        assertThat(((UserMessageEvent) sessions.current().events().get(0)).data().content())
                .isEqualTo(List.of(TextBlock.of("第二条")));
    }

    @Test
    void switchTo_不存在或路径穿越_拒绝() {
        var sessions = manager();
        sessions.current();
        assertThatThrownBy(() -> sessions.switchTo("no-such-session"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sessions.switchTo("../escape"))
                .isInstanceOf(IllegalArgumentException.class);
        // 失败后 current 保持不变
        assertThat(sessions.currentId()).isEqualTo(sessions.current().id());
    }

    @Test
    void list_按最近修改倒序_含事件数() {
        var sessions = manager();
        var first = sessions.current();
        first.append(UserMessageEvent.draft(List.of(TextBlock.of("a"))));
        sessions.createNew();
        var third = sessions.createNew();
        third.append(UserMessageEvent.draft(List.of(TextBlock.of("b"))));
        third.append(UserMessageEvent.draft(List.of(TextBlock.of("c"))));

        var list = sessions.list();
        assertThat(list).hasSize(3);
        assertThat(list.get(0).id()).isEqualTo(third.id()); // 最近修改在前
        assertThat(list.get(0).events()).isEqualTo(2);
    }

    @Test
    void 跨实例重开_默认选中最近会话_日志完整重放() {
        var first = manager();
        var session = first.current();
        session.append(UserMessageEvent.draft(List.of(TextBlock.of("重启前"))));
        var expected = session.events();

        // 模拟进程重启: 全新 SessionManager, 默认选回最近修改的会话
        var reopened = manager();
        assertThat(reopened.current().id()).isEqualTo(session.id());
        assertThat(reopened.current().events()).isEqualTo(expected);
    }
}
