package com.dsh.core.session.message;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 消息角色, 对应 dsh 的 role。 */
public enum Role {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system");

    private final String value;

    Role(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static Role from(String value) {
        for (Role role : values()) {
            if (role.value.equals(value)) {
                return role;
            }
        }
        throw new IllegalArgumentException("unknown role: " + value);
    }
}
