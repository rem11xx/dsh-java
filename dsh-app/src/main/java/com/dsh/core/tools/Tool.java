package com.dsh.core.tools;

import com.dsh.core.llm.ToolSchema;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具接口, 对应 dsh 的 ToolDefinition 的最小版本: 面向模型的三个字段 + 执行函数。
 *
 * <p>parameters() 返回标准 JSON Schema (type/properties/required) 的 object 节点,
 * 注册表通过 schemas() 把它投影成 ToolSchema 进入模型请求; execute 与拦截器等宿主元数据
 * 绝不会泄漏到模型请求里 (对应 dsh schemas() 的显式白名单投影)。
 *
 * <p>execute 抛出的异常由注册表流水线转成 isError 结果, 不向上传播 --
 * 工具调用失败不终止当前轮次, 把错误交还给模型自行决策。
 */
public interface Tool {

    /** 工具名, 全局唯一, 模型以此发起调用 */
    String name();

    /** 面向模型的工具描述 */
    String description();

    /** 参数 JSON Schema (object 根节点) */
    JsonNode parameters();

    /** 执行一次调用。参数自行校验, 非法参数建议返回 error 结果或抛出异常 */
    ToolResult execute(JsonNode args);
}
