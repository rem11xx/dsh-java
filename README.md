# dsh-java

DeepSeek Harness (dsh) 的最小 Java 复刻版: 用 Spring Boot 实现 dsh 的 4 个核心设计 (Session Log / Tools 注册表 / Agent Loop / 能力 seam), 每个都做到最简可演示。开发约定与里程碑见 [CLAUDE.md](CLAUDE.md)。

参考项目: https://github.com/deepseek-ai/deepseek-harness (本地参考文档在 docs/reference/, 已 gitignore)

## 核心概念对照

| dsh 概念 | 本项目实现 | 所在包 |
|---|---|---|
| Cordis ctx (插件共享上下文) | Spring ApplicationContext + 事件机制 + @Conditional 装配, 插件声明 PluginDescriptor 报到 | core.plugin |
| Session Log (仅追加事件日志) | SessionEvent 体系, 追加写 session.jsonl, 启动重放重建内存状态 | core.session |
| Tools 注册表 + 执行流水线 | Tool 接口 + JSON Schema 描述 + pre/execute/post 拦截链 | core.tools |
| Agent Loop (turn/step) | 状态机: turn/start -> LLM -> tool_calls -> 循环 -> turn/end, 全程落日志 | core.agent |
| LLM 适配器 seam | LlmAdapter 接口 + DeepSeek 默认实现, 可被插件整体替换 | core.llm |

设计原则 (来自 dsh, 全程遵守):

- **模型可见即已记录**: 任何进入模型请求的内容, 必须能从 session 日志重建。消息不从内存取, 每次都由日志投影 (deriveMessages)。
- **事件即扩展点**: SSE 推送、CLI trace 都是纯 @EventListener 消费者, loop 核心代码零改动。
- **换实现不改核心**: llm/工具通过接口 seam 替换, 插件模块演示。

## 模块结构

```
dsh-java/                 # 聚合器 pom
├── dsh-app/              # 宿主应用 (对插件一无所知)
│   └── com.dsh
│       ├── core
│       │   ├── session    # 事件定义 + JSONL 追加存储 + 重放/崩溃修复
│       │   ├── llm        # LlmAdapter 接口 + DeepSeek 实现 (OpenAI 兼容)
│       │   ├── tools      # Tool 接口 + 注册表 + 执行流水线 + 内置工具
│       │   ├── agent      # AgentLoop 状态机 (turn/step) + CLI 入口
│       │   └── plugin     # PluginDescriptor 报到机制
│       ├── web            # REST API + SSE 推送 + 单页演示 UI
│       └── config         # 配置属性类
└── dsh-plugin-stub/       # 演示插件 (整体替换 LLM 适配器与内置工具)
```

## 环境要求

- 运行时 JDK 21 (zulu-21); 
