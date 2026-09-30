package com.dsh.core.tools;

import java.util.List;

import com.dsh.config.DshProperties;
import com.dsh.core.tools.builtin.EchoTool;
import com.dsh.core.tools.builtin.ReadFileTool;
import com.dsh.core.tools.intercept.ReadFileSandboxInterceptor;
import com.dsh.core.tools.intercept.ResultTruncateInterceptor;
import com.dsh.core.tools.intercept.ToolLoggingInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 工具系统装配
 *
 * <ul>
 * <li>注册表本身 @ConditionalOnMissingBean -- 任何模块声明自己的 ToolRegistry 即整体替换;
 * <li>内置工具受 dsh.tools.builtins-enabled 开关控制 -- 插件模块可关掉内置, 用自己的 Tool bean 补位;
 * <li>所有 Tool/ToolInterceptor bean 自动汇入注册表 -- 加工具 = 加一个 bean, 不改核心代码。
 * </ul>
 */
@Configuration
public class ToolConfig {

    @Bean
    @ConditionalOnMissingBean(ToolRegistry.class)
    public ToolRegistry toolRegistry(ObjectMapper mapper, List<Tool> tools, List<ToolInterceptor> interceptors) {
        return new ToolRegistry(mapper, tools, interceptors);
    }

    @Bean
    @ConditionalOnProperty(name = "dsh.tools.builtins-enabled", havingValue = "true", matchIfMissing = true)
    public Tool echoTool() {
        return new EchoTool();
    }

    @Bean
    @ConditionalOnProperty(name = "dsh.tools.builtins-enabled", havingValue = "true", matchIfMissing = true)
    public Tool readFileTool() {
        return new ReadFileTool();
    }

    @Bean
    public ToolInterceptor readFileSandboxInterceptor(DshProperties properties) {
        return new ReadFileSandboxInterceptor(properties.getTools().getSandboxDir());
    }

    @Bean
    public ToolInterceptor toolLoggingInterceptor() {
        return new ToolLoggingInterceptor();
    }

    @Bean
    public ToolInterceptor resultTruncateInterceptor(DshProperties properties) {
        return new ResultTruncateInterceptor(properties.getTools().getMaxResultChars());
    }
}
