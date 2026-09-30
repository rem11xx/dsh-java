package com.dsh.core.llm;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 面向模型的工具描述, 进入 LLM 请求的 tools 字段, 对应 dsh 中组装进提示词的 tool schema。
 * parameters 是标准 JSON Schema (type/properties/required), 以 JsonNode 透传, 不做二次校验。
 */
public record ToolSchema(String name, String description, JsonNode parameters) {
}
