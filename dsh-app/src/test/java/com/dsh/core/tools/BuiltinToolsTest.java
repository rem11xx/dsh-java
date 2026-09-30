package com.dsh.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.dsh.core.tools.builtin.EchoTool;
import com.dsh.core.tools.builtin.ReadFileTool;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 内置示例工具测试: echo 回显, read_file 读取/截断/参数与文件错误。
 */
class BuiltinToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void echo_回显参数JSON() throws IOException {
        ToolResult result = new EchoTool().execute(MAPPER.readTree("{\"text\":\"你好\"}"));
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).isEqualTo("{\"text\":\"你好\"}");
    }

    @Test
    void read_file_正常读取() throws IOException {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "hello dsh");
        ToolResult result = new ReadFileTool().execute(
                MAPPER.readTree("{\"path\":\"" + file + "\"}"));
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).isEqualTo("hello dsh");
    }

    @Test
    void read_file_超长截断并标注总长() throws IOException {
        Path file = tempDir.resolve("long.txt");
        Files.writeString(file, "x".repeat(100));
        ToolResult result = new ReadFileTool().execute(
                MAPPER.readTree("{\"path\":\"" + file + "\",\"max_chars\":10}"));
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).startsWith("x".repeat(10)).contains("已截断").contains("100");
    }

    @Test
    void read_file_缺参数或文件不存在_返回错误结果() throws IOException {
        var tool = new ReadFileTool();
        assertThat(tool.execute(MAPPER.readTree("{}")).isError()).isTrue();
        assertThat(tool.execute(MAPPER.readTree("{\"path\":\"" + tempDir.resolve("nope") + "\"}")).isError()).isTrue();
    }
}
