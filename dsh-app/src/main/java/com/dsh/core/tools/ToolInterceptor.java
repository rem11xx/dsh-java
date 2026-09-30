package com.dsh.core.tools;

/**
 * 工具执行流水线的拦截器, 对应 dsh 的三个事件 waterfall:
 * tools/pre-execute (放行/拒绝), tools/execute (环绕分派), tools/post-execute (检查/替换结果)。
 *
 * <p>实现类按 Spring 的 Ordered/@Order 排序, 序号小者先执行:
 * pre 按序逐个询问, around 外层包裹内层, post 按序可逐个替换结果。
 * 三个方法都有默认实现, 实现类只覆盖关心的阶段。
 */
public interface ToolInterceptor {

    /**
     * pre 阶段: 派发前的策略闸门。返回 null 放行; 返回拒绝原因则短路整条流水线,
     * 原因会作为 isError 结果交还给模型。参数不可改写 (历史/审计/执行必须一致), 故只读。
     */
    default String preExecute(ToolExecution execution) {
        return null;
    }

    /**
     * around 阶段: 环绕分派, 可做超时/重试/计时。默认直接放行到下一层。
     * 拦截器异常会让本次调用以 isError 结果收场, 不影响其他调用。
     */
    default ToolResult aroundExecute(ToolExecution execution, ToolChain next) {
        return next.proceed();
    }

    /**
     * post 阶段: 检查或替换已产出的结果 (如脱敏/截断)。返回 null 表示保持原结果。
     */
    default ToolResult postExecute(ToolExecution execution, ToolResult result) {
        return result;
    }

    /** around 链的下一层, 最终到达工具函数体 */
    @FunctionalInterface
    interface ToolChain {

        ToolResult proceed();
    }
}
