package com.dsh.core.tools.intercept;

import java.nio.file.Path;

import com.dsh.core.tools.ToolExecution;
import com.dsh.core.tools.ToolInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * pre 阶段示例: read_file 的路径沙箱。目标路径解析后必须落在 baseDir 内,
 * 否则返回拒绝原因, 工具函数体根本不会被执行 -- 展示"策略前置, 拒绝即短路"。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ReadFileSandboxInterceptor implements ToolInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ReadFileSandboxInterceptor.class);

    private final Path baseDir;

    public ReadFileSandboxInterceptor(Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
    }

    @Override
    public String preExecute(ToolExecution execution) {
        if (!"read_file".equals(execution.name())) {
            return null;
        }
        var pathNode = execution.args().get("path");
        // 参数缺失/非法交给工具自己报错, 这里只管越界
        if (pathNode == null || !pathNode.isTextual()) {
            return null;
        }
        Path target = baseDir.resolve(pathNode.asText()).toAbsolutePath().normalize();
        if (!target.startsWith(baseDir)) {
            log.warn("read_file 越界拒绝: {} (baseDir={})", pathNode.asText(), baseDir);
            return "path 越界, 仅允许访问 " + baseDir + " 内的文件";
        }
        return null;
    }
}
