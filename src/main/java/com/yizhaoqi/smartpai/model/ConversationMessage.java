package com.yizhaoqi.smartpai.model;

import java.util.List;

/** Redis 中的结构化会话消息，兼容没有 sources 字段的旧历史数据。 */
public record ConversationMessage(
        String role,
        String content,
        String timestamp,
        List<ConversationSource> sources
) {
    public ConversationMessage {
        sources = List.copyOf(sources == null ? List.of() : sources);
    }
}
