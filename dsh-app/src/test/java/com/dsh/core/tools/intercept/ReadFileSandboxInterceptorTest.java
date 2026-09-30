package com.dsh.core.tools.intercept;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.dsh.core.tools.ToolExecution;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * read_file 沙箱拦截器测试: 界内放行, 相对/绝对路径越界拒绝, 其他工具不受影响。
 */
class ReadFileSandboxInterceptorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private String pre(Path baseDir, String toolName, String argsJson) throws Exception {
        return new ReadFileSandboxInterceptor(baseDir)
                .preExecute(new ToolExecution("c1", toolName, MAPPER.readTree(argsJson)));
    }

    @Test
    void 界内路径_放行() throws Exception {
        assertThat(pre(tempDir, "read_file", "{\"path\":\"a.txt\"}")).isNull();
        assertThat(pre(tempDir, "read_file", "{\"path\":\"sub/../a.txt\"}")).isNull();
    }

    @Test
    void 相对路径越界_拒绝() throws Exception {
        assertThat(pre(tempDir, "read_file", "{\"path\":\"../secret.txt\"}")).contains("越界");
    }

    @Test
    void 绝对路径越界_拒绝() throws Exception {
        assertThat(pre(tempDir, "read_file", "{\"path\":\"/etc/passwd\"}")).contains("越界");
    }

    @Test
    void 其他工具_不检查() throws Exception {
        assertThat(pre(tempDir, "echo", "{\"text\":\"../x\"}")).isNull();
    }

    @Test
    void 参数缺失_交给工具自报错误() throws Exception {
        assertThat(pre(tempDir, "read_file", "{}")).isNull();
        assertThat(pre(tempDir, "read_file",
                JsonNodeFactory.instance.objectNode().put("path", 42).toString())).isNull();
    }
}
