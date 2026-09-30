package com.dsh.core.tools.intercept;

import com.dsh.core.tools.ToolExecution;
import com.dsh.core.tools.ToolInterceptor;
import com.dsh.core.tools.ToolResult;

/**
 * post 阶段示例: 结果截断安全网。无论工具自己是否截断, 进入模型上下文的结果
 * 一律不超过 maxChars, 防止单次工具输出挤爆上下文 -- 展示"post 可检查/替换结果"。
 */
public class ResultTruncateInterceptor implements ToolInterceptor {

    private final int maxChars;

    public ResultTruncateInterceptor(int maxChars) {
        this.maxChars = maxChars;
    }

    @Override
    public ToolResult postExecute(ToolExecution execution, ToolResult result) {
        if (result.content() == null || result.content().length() <= maxChars) {
            return result;
        }
        String truncated = result.content().substring(0, maxChars)
                + "\n...(结果超长, 已由拦截器截断至 " + maxChars + " 字符)";
        return new ToolResult(truncated, result.isError());
    }
}
