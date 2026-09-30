package com.dsh.core.tools.builtin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.dsh.core.tools.Tool;
import com.dsh.core.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 示例工具: 读取文本文件, 超长截断。参数校验自行完成 (对应 dsh "工具自验参数" 的约定),
 * 非法输入返回 error 结果而不是抛异常 -- 错误交还模型, 让它自己换路径或改参数。
 */
public class ReadFileTool implements Tool {

    static final int DEFAULT_MAX_CHARS = 4096;

    private static final JsonNode PARAMETERS = buildParameters();

    private static JsonNode buildParameters() {
        var f = JsonNodeFactory.instance;
        ObjectNode path = f.objectNode().put("type", "string").put("description", "文件路径");
        ObjectNode maxChars = f.objectNode().put("type", "integer")
                .put("description", "最多返回的字符数, 默认 " + DEFAULT_MAX_CHARS);
        ObjectNode properties = f.objectNode();
        properties.set("path", path);
        properties.set("max_chars", maxChars);
        ObjectNode schema = f.objectNode().put("type", "object");
        schema.set("properties", properties);
        schema.set("required", f.arrayNode().add("path"));
        return schema;
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "读取一个文本文件的内容, 超出 max_chars 部分截断";
    }

    @Override
    public JsonNode parameters() {
        return PARAMETERS;
    }

    @Override
    public ToolResult execute(JsonNode args) {
        JsonNode pathNode = args.get("path");
        if (pathNode == null || !pathNode.isTextual() || pathNode.asText().isBlank()) {
            return ToolResult.error("缺少必填参数 path (string)");
        }
        int maxChars = args.path("max_chars").asInt(DEFAULT_MAX_CHARS);
        if (maxChars <= 0) {
            return ToolResult.error("max_chars 必须为正整数");
        }
        Path path = Path.of(pathNode.asText());
        if (!Files.isRegularFile(path)) {
            return ToolResult.error("文件不存在: " + path);
        }
        String content;
        try {
            content = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return ToolResult.error("读取失败: " + e.getMessage());
        }
        if (content.length() > maxChars) {
            content = content.substring(0, maxChars) + "\n...(已截断, 全文共 " + content.length() + " 字符)";
        }
        return ToolResult.of(content);
    }
}
