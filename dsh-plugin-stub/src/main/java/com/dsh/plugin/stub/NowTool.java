package com.dsh.plugin.stub;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.dsh.core.tools.Tool;
import com.dsh.core.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 插件自带工具: 返回当前时间。无参数, 实现本身无趣,
 * 有趣的是它如何进入模型视野 -- 只是一个 Tool bean, ToolRegistry 自动汇入,
 * 宿主的 ToolConfig 一行未改。
 */
public class NowTool implements Tool {

    private static final JsonNode PARAMETERS = buildParameters();

    private static JsonNode buildParameters() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode().put("type", "object");
        schema.set("properties", JsonNodeFactory.instance.objectNode());
        return schema;
    }

    @Override
    public String name() {
        return "now";
    }

    @Override
    public String description() {
        return "返回服务器当前时间 (插件 dsh-plugin-stub 提供)";
    }

    @Override
    public JsonNode parameters() {
        return PARAMETERS;
    }

    @Override
    public ToolResult execute(JsonNode args) {
        return ToolResult.of(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
    }
}
