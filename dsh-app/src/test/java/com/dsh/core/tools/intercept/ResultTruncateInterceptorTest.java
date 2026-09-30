package com.dsh.core.tools.intercept;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.dsh.core.tools.ToolExecution;
import com.dsh.core.tools.ToolResult;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * 结果截断拦截器测试: 超长截断并保留 isError 语义, 短结果原样放行。
 */
class ResultTruncateInterceptorTest {

    private ToolResult post(int maxChars, ToolResult result) {
        return new ResultTruncateInterceptor(maxChars).postExecute(
                new ToolExecution("c1", "t", JsonNodeFactory.instance.objectNode()), result);
    }

    @Test
    void 超长内容_截断并标注() {
        ToolResult result = post(10, ToolResult.of("x".repeat(100)));
        assertThat(result.content()).hasSizeLessThan(100).startsWith("x".repeat(10)).contains("已由拦截器截断");
        assertThat(result.isError()).isFalse();
    }

    @Test
    void 错误结果同样截断_isError保留() {
        ToolResult result = post(5, ToolResult.error("e".repeat(50)));
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).startsWith("eeeee").contains("已由拦截器截断");
    }

    @Test
    void 未超长_原样放行() {
        ToolResult result = post(100, ToolResult.of("short"));
        assertThat(result.content()).isEqualTo("short");
    }
}
