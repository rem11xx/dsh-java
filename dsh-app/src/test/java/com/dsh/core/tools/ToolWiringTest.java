package com.dsh.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.dsh.core.llm.ToolSchema;

/**
 * Spring 装配冒烟: 内置工具自动汇入注册表, 沙箱 pre 拦截与截断 post 拦截在真实 bean 上生效。
 */
@SpringBootTest
class ToolWiringTest {

    @Autowired
    ToolRegistry registry;

    @Test
    void 内置工具已注册_schemas可进模型请求() {
        assertThat(registry.names()).contains("echo", "read_file");
        assertThat(registry.schemas())
                .extracting(ToolSchema::name)
                .contains("echo", "read_file");
    }

    @Test
    void echo经完整流水线回显() {
        ToolResult result = registry.execute("c1", "echo", "{\"text\":\"你好\"}");
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).isEqualTo("{\"text\":\"你好\"}");
    }

    @Test
    void read_file越界路径被沙箱拒绝_函数体不执行() {
        ToolResult result = registry.execute("c2", "read_file", "{\"path\":\"../pom.xml\"}");
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("denied").contains("越界");
    }

    @Test
    void read_file界内文件正常读取() {
        ToolResult result = registry.execute("c3", "read_file",
                "{\"path\":\"pom.xml\",\"max_chars\":50}");
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).startsWith("<?xml");
    }
}
