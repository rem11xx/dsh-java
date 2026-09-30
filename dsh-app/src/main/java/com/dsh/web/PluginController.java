package com.dsh.web;

import java.util.List;
import java.util.Map;

import com.dsh.core.llm.LlmAdapter;
import com.dsh.core.plugin.PluginDescriptor;
import com.dsh.core.tools.ToolRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 插件面板端点: 展示当前生效的 LLM provider / 工具集 / 已登记的插件。
 *
 * <p>D7 演示的关键观察点 -- 同一条命令分别以宿主/宿主+插件启动,
 * 这里返回的三块内容肉眼可见地不同, 而 core 代码一行未改。
 */
@RestController
public class PluginController {

    private final LlmAdapter llm;
    private final ToolRegistry tools;
    private final ObjectProvider<PluginDescriptor> plugins;

    public PluginController(LlmAdapter llm, ToolRegistry tools, ObjectProvider<PluginDescriptor> plugins) {
        this.llm = llm;
        this.tools = tools;
        this.plugins = plugins;
    }

    @GetMapping("/api/plugins")
    public Map<String, Object> plugins() {
        List<PluginDescriptor> descriptors = plugins.orderedStream().toList();
        return Map.of(
                "llmProvider", llm.providerName(),
                "tools", tools.names(),
                "plugins", descriptors);
    }
}
