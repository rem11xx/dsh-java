package com.dsh.core.tools.builtin;

import com.dsh.core.tools.Tool;
import com.dsh.core.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 最简示例工具: 原样回显参数 JSON。用于验证注册表/拦截链/模型工具调用全链路
 */
public class EchoTool implements Tool {

    private static final JsonNode PARAMETERS = buildParameters();

    /**
     * json
     * {
     *   "type": "object",
     *   "properties": { "text": { "type": "string", "description": "要回显的文本" } },
     *   "required": ["text"]
     * }
     */
    private static JsonNode buildParameters() {
        var f = JsonNodeFactory.instance;
        var text = f.objectNode().put("type", "string").put("description", "要回显的文本");
        ObjectNode schema = f.objectNode().put("type", "object");
        schema.set("properties", f.objectNode().set("text", text));
        schema.set("required", f.arrayNode().add("text"));
        return schema;
    }

    @Override
    public String name() {
        return "echo";
    }

    @Override
    public String description() {
        return "原样返回输入参数, 用于测试";
    }

    @Override
    public JsonNode parameters() {
        return PARAMETERS;
    }

    @Override
    public ToolResult execute(JsonNode args) {
        return ToolResult.of(args.toString());
    }
}
