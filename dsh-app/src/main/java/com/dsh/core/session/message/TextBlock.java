package com.dsh.core.session.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 文本内容块。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TextBlock(String type, String text) implements ContentBlock {

    public static final String TYPE_TEXT = "text";

    public static TextBlock of(String text) {
        return new TextBlock(TYPE_TEXT, text);
    }

    public TextBlock {
        if (type == null) {
            type = TYPE_TEXT;
        }
    }
}
