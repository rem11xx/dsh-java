package com.dsh.core.session.store;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * JSONL 文件首行 header 记录, 对应 dsh 的 session header。
 * 首行不参与事件 seq 编号, 事件从第二行起 seq=0。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionHeader(String type, int version, String id, long createdAt) {

    public static final String TYPE_SESSION = "session";
    public static final int VERSION = 1;
}
