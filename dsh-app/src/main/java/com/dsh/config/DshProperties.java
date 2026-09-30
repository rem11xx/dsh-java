package com.dsh.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 项目配置属性。对应 application.yml 中 dsh 前缀。
 */
@ConfigurationProperties(prefix = "dsh")
public class DshProperties {

    private final Llm llm = new Llm();
    private final Session session = new Session();
    private final Tools tools = new Tools();
    private final Agent agent = new Agent();

    public Llm getLlm() {
        return llm;
    }

    public Session getSession() {
        return session;
    }

    public Tools getTools() {
        return tools;
    }

    public Agent getAgent() {
        return agent;
    }

    /**
     * AgentLoop 配置。
     */
    public static class Agent {

        /** 单轮内最大 step 数, 防止模型无限循环调工具 */
        private int maxSteps = 8;

        /** 系统提示词, 首次调用 LLM 前落一条 system/message 事件; 空则不加 */
        private String systemPrompt = "";

        public int getMaxSteps() {
            return maxSteps;
        }

        public void setMaxSteps(int maxSteps) {
            this.maxSteps = maxSteps;
        }

        public String getSystemPrompt() {
            return systemPrompt;
        }

        public void setSystemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
        }
    }

    /**
     * 会话日志配置。
     */
    public static class Session {

        /** 会话根目录, 每个会话占一个子目录 <sessionId>/session.jsonl */
        private java.nio.file.Path dir = java.nio.file.Path.of("./data/sessions");

        public java.nio.file.Path getDir() {
            return dir;
        }

        public void setDir(java.nio.file.Path dir) {
            this.dir = dir;
        }
    }

    /**
     * 工具系统配置。
     */
    public static class Tools {

        /** 是否注册内置示例工具 (D7 插件模块可关闭后用自己的工具补位) */
        private boolean builtinsEnabled = true;

        /** read_file 的沙箱根目录, 越界路径在 pre 阶段被拒绝 */
        private java.nio.file.Path sandboxDir = java.nio.file.Path.of(".");

        /** 单个工具结果进入模型上下文前的最大字符数, 超出由 post 拦截器截断 */
        private int maxResultChars = 8192;

        public boolean isBuiltinsEnabled() {
            return builtinsEnabled;
        }

        public void setBuiltinsEnabled(boolean builtinsEnabled) {
            this.builtinsEnabled = builtinsEnabled;
        }

        public java.nio.file.Path getSandboxDir() {
            return sandboxDir;
        }

        public void setSandboxDir(java.nio.file.Path sandboxDir) {
            this.sandboxDir = sandboxDir;
        }

        public int getMaxResultChars() {
            return maxResultChars;
        }

        public void setMaxResultChars(int maxResultChars) {
            this.maxResultChars = maxResultChars;
        }
    }

    /**
     * LLM 适配器配置。v1 使用火山引擎 coding plan 的 OpenAI 兼容端点。
     */
    public static class Llm {

        /** OpenAI 兼容端点根路径, chat/completions 会拼在其后 */
        private String baseUrl = "https://ark.cn-beijing.volces.com/api/coding/v3";

        /** API Key, 从环境变量 ARK_API_KEY 读取, 不允许写死入库 */
        private String apiKey = "";

        /** 模型名, 按开通的 coding plan 填写 */
        private String model = "";

        /** 请求超时秒数 */
        private int timeoutSeconds = 120;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }
}
