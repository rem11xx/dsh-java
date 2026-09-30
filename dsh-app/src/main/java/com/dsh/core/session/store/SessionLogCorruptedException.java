package com.dsh.core.session.store;

/** 会话日志结构损坏 (header 非法 / seq 断裂 / 事件结构不闭合), 抛异常而不是静默修复。 */
public class SessionLogCorruptedException extends RuntimeException {

    public SessionLogCorruptedException(String message) {
        super(message);
    }

    public SessionLogCorruptedException(String message, Throwable cause) {
        super(message, cause);
    }
}
