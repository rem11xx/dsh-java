package com.dsh.plugin.stub;

import java.nio.file.Path;

import com.dsh.core.llm.DeepSeekLlmAdapter;
import com.dsh.core.llm.LlmAdapter;
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
 * @ConditionalOnProperty 总开关: 插件仍在 classpath 上, 仅靠配置关闭 --
 * 装配结果应与"插件不存在"完全一致 (内置工具 + DeepSeek 适配器 + 空插件面板)。
 */
@SpringBootTest(properties = "dsh.plugin.stub.enabled=false")
class StubPluginDisabledTest {

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

    @Test
    void 回到默认装配() {
        assertThat(llm).isInstanceOf(DeepSeekLlmAdapter.class);
        assertThat(tools.names()).containsExactlyInAnyOrder("echo", "read_file");
        assertThat(pluginController.plugins().get("plugins")).isEqualTo(java.util.List.of());
    }
}
