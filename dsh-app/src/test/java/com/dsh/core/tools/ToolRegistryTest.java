package com.dsh.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;

import com.dsh.core.llm.ToolSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 注册表与执行流水线单元测试: 未知工具/短路拒绝/环绕顺序/结果替换/异常归一化。
 * CLAUDE.md 要求工具拦截必须有测试, 此处即 D8 中该要求的核心用例。
 */
class ToolRegistryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 记录是否被真正执行的假工具 */
    private static final class RecordingTool implements Tool {

        final String name;
        boolean invoked;
        ToolResult result = ToolResult.of("ok");
        RuntimeException throwOnExecute;

        RecordingTool(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "test:" + name;
        }

        @Override
        public JsonNode parameters() {
            return JsonNodeFactory.instance.objectNode().put("type", "object");
        }

        @Override
        public ToolResult execute(JsonNode args) {
            invoked = true;
            if (throwOnExecute != null) {
                throw throwOnExecute;
            }
            return result;
        }
    }

    private ToolExecution exec(String name) {
        return new ToolExecution("call-1", name, JsonNodeFactory.instance.objectNode());
    }

    @Test
    void 未知工具_归一化为错误结果_不上抛() {
        var registry = new ToolRegistry(MAPPER, List.of(), List.of());
        ToolResult result = registry.execute(exec("nope"));
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("unknown tool").contains("nope");
    }

    @Test
    void schemas_只投影白名单三字段() {
        var tool = new RecordingTool("echo");
        var registry = new ToolRegistry(MAPPER, List.of(tool), List.of());
        var schemas = registry.schemas();
        assertThat(schemas).hasSize(1);
        var schema = schemas.get(0);
        assertThat(schema.name()).isEqualTo("echo");
        assertThat(schema.description()).isEqualTo("test:echo");
        assertThat(schema.parameters()).isEqualTo(tool.parameters());
        // ToolSchema 是三组件 record, 天然不可能携带 execute 等宿主字段
        assertThat(ToolSchema.class.getRecordComponents()).hasSize(3);
    }

    @Test
    void 重复注册同名工具_启动即失败() {
        var registry = new ToolRegistry(MAPPER, List.of(new RecordingTool("dup")), List.of());
        assertThatThrownBy(() -> registry.register(new RecordingTool("dup")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dup");
    }

    @Test
    void pre拒绝_短路流水线_函数体不执行() {
        var tool = new RecordingTool("danger");
        var deny = new ToolInterceptor() {
            @Override
            public String preExecute(ToolExecution execution) {
                return "危险操作";
            }
        };
        var registry = new ToolRegistry(MAPPER, List.of(tool), List.of(deny));
        ToolResult result = registry.execute(exec("danger"));
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("denied").contains("危险操作");
        assertThat(tool.invoked).as("被拒绝的调用不应执行函数体").isFalse();
    }

    @Test
    void pre依次询问_靠前拦截器先拒绝() {
        var tool = new RecordingTool("t");
        List<String> asked = new ArrayList<>();
        var first = new ToolInterceptor() {
            @Override
            public String preExecute(ToolExecution e) {
                asked.add("first");
                return null;
            }
        };
        var second = new ToolInterceptor() {
            @Override
            public String preExecute(ToolExecution e) {
                asked.add("second");
                return "第二个拦下";
            }
        };
        var registry = new ToolRegistry(MAPPER, List.of(tool), List.of(first, second));
        assertThat(registry.execute(exec("t")).content()).contains("第二个拦下");
        assertThat(asked).containsExactly("first", "second");
        assertThat(tool.invoked).isFalse();
    }

    @Test
    void around环绕_排序靠前者在外层_可见整条链路() {
        var tool = new RecordingTool("t");
        List<String> trace = new ArrayList<>();
        var outer = new ToolInterceptor() {
            @Override
            public ToolResult aroundExecute(ToolExecution e, ToolChain next) {
                trace.add("outer>in");
                var r = next.proceed();
                trace.add("outer<out");
                return r;
            }
        };
        var inner = new ToolInterceptor() {
            @Override
            public ToolResult aroundExecute(ToolExecution e, ToolChain next) {
                trace.add("inner>in");
                var r = next.proceed();
                trace.add("inner<out");
                return r;
            }
        };
        var registry = new ToolRegistry(MAPPER, List.of(tool), List.of(outer, inner));
        assertThat(registry.execute(exec("t")).isError()).isFalse();
        assertThat(trace).containsExactly("outer>in", "inner>in", "inner<out", "outer<out");
    }

    @Test
    void post替换结果_拦截器排序生效() {
        var tool = new RecordingTool("t");
        // Ordered 高优先级 (小序号) 先执行
        var ordered = new ToolInterceptor() {
            @Override
            public ToolResult postExecute(ToolExecution e, ToolResult r) {
                return new ToolResult("[" + r.content() + "]", r.isError());
            }
        };
        var plain = new ToolInterceptor() {
            @Override
            public ToolResult postExecute(ToolExecution e, ToolResult r) {
                return new ToolResult(r.content() + "!", r.isError());
            }
        };
        var registry = new ToolRegistry(MAPPER, List.of(tool), List.of(plain, new OrderedInterceptor(ordered)));
        assertThat(registry.execute(exec("t")).content()).isEqualTo("[ok]!");
    }

    @Test
    void 工具抛异常_转错误结果_post仍能收到() {
        var tool = new RecordingTool("boom");
        tool.throwOnExecute = new IllegalStateException("炸了");
        List<ToolResult> seen = new ArrayList<>();
        var post = new ToolInterceptor() {
            @Override
            public ToolResult postExecute(ToolExecution e, ToolResult r) {
                seen.add(r);
                return r;
            }
        };
        var registry = new ToolRegistry(MAPPER, List.of(tool), List.of(post));
        ToolResult result = registry.execute(exec("boom"));
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("炸了");
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).isError()).isTrue();
    }

    @Test
    void 非法JSON参数_转错误结果() {
        var registry = new ToolRegistry(MAPPER, List.of(new RecordingTool("t")), List.of());
        ToolResult result = registry.execute("call-9", "t", "{not json");
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("arguments 不是合法 JSON");
    }

    @Test
    void 字符串参数入口_合法JSON正常执行() {
        var registry = new ToolRegistry(MAPPER, List.of(new RecordingTool("t")), List.of());
        ToolResult result = registry.execute("call-2", "t", "{\"a\":1}");
        assertThat(result.isError()).isFalse();
    }

    /** 用 Ordered 接口给拦截器一个明确的高优先级 */
    private record OrderedInterceptor(ToolInterceptor delegate) implements ToolInterceptor, Ordered {

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }

        @Override
        public String preExecute(ToolExecution execution) {
            return delegate.preExecute(execution);
        }

        @Override
        public ToolResult aroundExecute(ToolExecution execution, ToolChain next) {
            return delegate.aroundExecute(execution, next);
        }

        @Override
        public ToolResult postExecute(ToolExecution execution, ToolResult result) {
            return delegate.postExecute(execution, result);
        }
    }
}
