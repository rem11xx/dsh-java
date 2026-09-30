package com.dsh.core.agent;

import com.dsh.config.DshProperties;
import com.dsh.core.llm.LlmAdapter;
import com.dsh.core.session.SessionManager;
import com.dsh.core.tools.ToolRegistry;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AgentLoop 装配。与 llm/tools 同一套: 依赖全部是接口/注册表,
 * 换 provider/换工具都不影响 loop 本身
 */
@Configuration
public class AgentConfig {

    @Bean
    public AgentLoop agentLoop(SessionManager sessions, LlmAdapter llm, ToolRegistry tools,
                               DshProperties properties, ApplicationEventPublisher publisher) {
        return new AgentLoop(sessions, llm, tools, properties, publisher);
    }
}
