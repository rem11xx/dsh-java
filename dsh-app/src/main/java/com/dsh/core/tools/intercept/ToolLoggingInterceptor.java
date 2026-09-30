package com.dsh.core.tools.intercept;

import com.dsh.core.tools.ToolExecution;
import com.dsh.core.tools.ToolInterceptor;
import com.dsh.core.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * around 阶段示例: 计时与调用日志。排序最低 (最内层之外的最外层环绕),
 * 因此记录的是整条流水线的耗时。
 */
@Order(Ordered.LOWEST_PRECEDENCE)
public class ToolLoggingInterceptor implements ToolInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ToolLoggingInterceptor.class);

    @Override
    public ToolResult aroundExecute(ToolExecution execution, ToolChain next) {
        long start = System.nanoTime();
        ToolResult result = next.proceed();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        log.info("tool {} {} 耗时 {}ms (callId={})",
                execution.name(), result.isError() ? "失败" : "成功", elapsedMs, execution.callId());
        return result;
    }
}
