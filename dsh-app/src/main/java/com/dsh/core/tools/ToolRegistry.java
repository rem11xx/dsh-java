package com.dsh.core.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.dsh.core.llm.ToolSchema;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.OrderComparator;

/**
 * 工具注册表 + 执行流水线, 对应 dsh 的 ctx.tools (ToolRuntime) 的最小版本。
 *
 * <p>执行链: 查找工具 -> pre 闸门 (任一拒绝即短路) -> around 环绕分派 -> 工具函数体
 * -> post 检查/替换 -> 返回结果。未知工具与各级异常一律转为 isError 结果,
 * 绝不向上抛 -- 调用失败不终止当前轮次, 错误交还模型自行决策。
 *
 * <p>schemas() 是"模型可见即已记录"原则在工具侧的落地: 只投影 name/description/parameters
 * 三个白名单字段, execute 函数与拦截器元数据不会进入模型请求。
 */
public final class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final ObjectMapper mapper;
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final List<ToolInterceptor> interceptors;

    public ToolRegistry(ObjectMapper mapper, List<Tool> tools, List<ToolInterceptor> interceptors) {
        this.mapper = mapper;
        // OrderComparator 兼容 Ordered 接口与 @Order 注解, 序号小者先执行
        this.interceptors = interceptors == null ? List.of()
                : interceptors.stream().sorted(OrderComparator.INSTANCE).toList();
        if (tools != null) {
            tools.forEach(this::register);
        }
    }

    /** 注册工具, 同名重复注册直接失败 (对应 dsh 注册表的同层去重约束) */
    public synchronized void register(Tool tool) {
        var prev = tools.putIfAbsent(tool.name(), tool);
        if (prev != null) {
            throw new IllegalStateException("工具重名: " + tool.name() + " 已注册 " + prev.getClass().getName());
        }
    }

    public synchronized Optional<Tool> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public synchronized List<String> names() {
        return List.copyOf(tools.keySet());
    }

    /** 面向模型的投影: 每个可见工具一份白名单 ToolSchema, 不含任何宿主元数据 */
    public synchronized List<ToolSchema> schemas() {
        return tools.values().stream()
                .map(t -> new ToolSchema(t.name(), t.description(), t.parameters()))
                .toList();
    }

    /**
     * 便捷入口: 以模型产出的原始 arguments JSON 字符串执行。
     * arguments 非法 JSON 时不抛异常, 转为 isError 结果交还模型 (留痕在 session 日志里的仍是原始串)。
     */
    public ToolResult execute(String callId, String name, String arguments) {
        JsonNode args;
        try {
            args = arguments == null || arguments.isBlank() ? mapper.createObjectNode() : mapper.readTree(arguments);
        } catch (JsonProcessingException e) {
            return ToolResult.error("arguments 不是合法 JSON: " + e.getOriginalMessage());
        }
        return execute(new ToolExecution(callId, name, args));
    }

    /** 主流水线。任何一步失败都归一化为 isError 的 ToolResult, 不向上抛异常 */
    public ToolResult execute(ToolExecution execution) {
        Tool tool;
        synchronized (this) {
            tool = tools.get(execution.name());
        }
        if (tool == null) {
            return ToolResult.error("unknown tool: " + execution.name());
        }

        // pre: 按序逐个询问, 任一返回拒绝原因即短路
        for (var interceptor : interceptors) {
            String reason;
            try {
                reason = interceptor.preExecute(execution);
            } catch (RuntimeException e) {
                log.warn("pre-execute 拦截器 {} 异常", interceptor.getClass().getSimpleName(), e);
                return ToolResult.error("pre-execute 拦截器异常: " + e.getMessage());
            }
            if (reason != null) {
                log.debug("工具调用被拒绝: {} 原因: {}", execution.name(), reason);
                return ToolResult.error("denied: " + reason);
            }
        }

        // around + 函数体: 从最后一个拦截器向前包裹, 排序靠前者在外层
        ToolResult result;
        try {
            result = dispatch(execution, tool);
        } catch (RuntimeException e) {
            log.warn("工具 {} 执行异常", execution.name(), e);
            result = ToolResult.error("工具执行异常: " + e.getMessage());
        }

        // post: 按序检查/替换
        for (var interceptor : interceptors) {
            try {
                var replaced = interceptor.postExecute(execution, result);
                if (replaced != null) {
                    result = replaced;
                }
            } catch (RuntimeException e) {
                log.warn("post-execute 拦截器 {} 异常", interceptor.getClass().getSimpleName(), e);
                return ToolResult.error("post-execute 拦截器异常: " + e.getMessage());
            }
        }
        return result;
    }

    private ToolResult dispatch(ToolExecution execution, Tool tool) {
        ToolInterceptor.ToolChain chain = () -> tool.execute(execution.args());
        for (int i = interceptors.size() - 1; i >= 0; i--) {
            var interceptor = interceptors.get(i);
            var next = chain;
            chain = () -> interceptor.aroundExecute(execution, next);
        }
        return chain.proceed();
    }
}
