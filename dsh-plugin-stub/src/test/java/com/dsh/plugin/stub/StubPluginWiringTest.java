package com.dsh.plugin.stub;

import java.nio.file.Path;

import com.dsh.core.agent.AgentLoop;
import com.dsh.core.llm.LlmAdapter;
import com.dsh.core.session.SessionManager;
import com.dsh.core.session.event.ToolCallEvent;
import com.dsh.core.session.event.TurnEndEvent;
import com.dsh.core.tools.ToolRegistry;
import com.dsh.web.PluginController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D7 核心验证: 插件模块与宿主同 classpath 时的装配结果。
 * 不发任何网络请求 -- 整个上下文离线可跑本身就是演示点。
 */
@SpringBootTest(properties = "dsh.tools.builtins-enabled=false")
class StubPluginWiringTest {

    @TempDir
    static Path dir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("dsh.session.dir", () -> dir.toAbsolutePath().toString());
    }

    @Autowired
    LlmAdapter llm;

    @Autowired
    ToolRegistry tools;

    @Autowired
    PluginController pluginController;

    @Autowired
    AgentLoop loop;

    @Autowired
    SessionManager sessions;

    @Test
    void 注入的适配器是插件实现() {
        assertThat(llm).isInstanceOf(StubLlmAdapter.class);
        assertThat(llm.providerName()).isEqualTo("stub");
    }

    @Test
    void 内置工具被关掉_只剩插件工具() {
        assertThat(tools.names()).containsExactly("now");
    }

    @Test
    void 插件面板展示替换结果() {
        var panel = pluginController.plugins();

        assertThat(panel.get("llmProvider")).isEqualTo("stub");
        assertThat(panel.get("tools")).isEqualTo(java.util.List.of("now"));
        @SuppressWarnings("unchecked")
        var descriptors = (java.util.List<com.dsh.core.plugin.PluginDescriptor>) panel.get("plugins");
        assertThat(descriptors).hasSize(1);
        assertThat(descriptors.get(0).name()).isEqualTo("dsh-plugin-stub");
    }

    @Test
    void 离线端到端_两轮都走完工具闭环() {
        var first = loop.run("现在几点?");
        assertThat(first.reason()).isEqualTo("completed");
        assertThat(first.text()).contains("[stub] 工具 now 返回:");

        // 剧本无状态: 第二轮重新从 tool_call 开始, 而不是直接收尾
        var second = loop.run("再问一次");
        assertThat(second.reason()).isEqualTo("completed");
        assertThat(second.text()).contains("[stub] 工具 now 返回:");

        // 日志可重建: 两轮各有一条工具调用与闭合的 turn/end
        var events = sessions.current().events();
        assertThat(events.stream().filter(ToolCallEvent.class::isInstance)).hasSize(2);
        assertThat(events.stream().filter(TurnEndEvent.class::isInstance)).hasSize(2);
    }
}
