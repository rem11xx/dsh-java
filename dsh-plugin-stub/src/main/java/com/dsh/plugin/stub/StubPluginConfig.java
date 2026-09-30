package com.dsh.plugin.stub;

import com.dsh.core.llm.LlmAdapter;
import com.dsh.core.plugin.PluginDescriptor;
import com.dsh.core.tools.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * dsh-plugin-stub 的全部装配。位于 com.dsh 包树下, 宿主 @SpringBootApplication 的
 * 组件扫描天然覆盖 -- 插件 jar 上了 classpath 就会被扫到, 无需宿主做任何登记。
 *
 * <p>三个要点 (D7 分享会讲的就是这里):
 * <ul>
 * <li><b>@Primary 替换默认适配器</b>: 宿主的 DeepSeek 适配器是
 *     @ConditionalOnMissingBean(LlmAdapter.class), 它与插件配置类的解析顺序
 *     在不同运行环境下并不保证 -- @Primary 让注入点在"两者并存"时也确定拿到
 *     插件实现, 两种顺序下结果一致, 宿主代码零改动;</li>
 * <li><b>@ConditionalOnProperty 总开关</b>: dsh.plugin.stub.enabled=false 可以
 *     在插件仍在 classpath 的情况下一键关闭, 演示 @Conditional 装配;</li>
 * <li><b>PluginDescriptor 报到</b>: 声明一个描述 bean, 宿主 /api/plugins 就能展示,
 *     对应 dsh Cordis ctx 的插件登记。</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(prefix = "dsh.plugin.stub", name = "enabled", havingValue = "true", matchIfMissing = true)
public class StubPluginConfig {

    @Bean
    @Primary
    public LlmAdapter stubLlmAdapter(@Value("${dsh.plugin.stub.tool-name:now}") String toolName) {
        return new StubLlmAdapter(toolName);
    }

    @Bean
    public Tool nowTool() {
        return new NowTool();
    }

    @Bean
    public PluginDescriptor stubPluginDescriptor() {
        return new PluginDescriptor("dsh-plugin-stub", "脚本化 LLM 适配器 + now 工具, 离线替换 DeepSeek seam");
    }

    /** 事件即扩展点: 插件不改 loop 一行代码, 纯靠监听广播事件观察轮次 */
    @Bean
    public StubTraceListener stubTraceListener() {
        return new StubTraceListener();
    }
}
