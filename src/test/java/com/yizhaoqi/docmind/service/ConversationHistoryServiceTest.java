package com.yizhaoqi.docmind.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.docmind.config.AiProperties;
import com.yizhaoqi.docmind.model.ConversationMessage;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ConversationHistoryServiceTest {

    @Test
    void removesHistoricalCitationIdsBeforeSendingHistoryToModel() {
        AiProperties properties = new AiProperties();
        ConversationHistoryService service = new ConversationHistoryService(
                mock(RedisTemplate.class), new ObjectMapper(), properties);
        List<ConversationMessage> history = List.of(
                new ConversationMessage("assistant", "需要提前7天【来源#1】，并审批【S2】。",
                        "2026-09-30T10:00:00", List.of()));

        List<Map<String, String>> result = service.historyForAnswer(history);

        assertThat(result).containsExactly(Map.of(
                "role", "assistant", "content", "需要提前7天，并审批。"));
    }

    @Test
    void usesRecentTurnsForQueryRewrite() {
        AiProperties properties = new AiProperties();
        properties.getQueryRewrite().setRecentTurns(1);
        ConversationHistoryService service = new ConversationHistoryService(
                mock(RedisTemplate.class), new ObjectMapper(), properties);
        List<ConversationMessage> history = List.of(
                message("user", "旧问题"), message("assistant", "旧回答"),
                message("user", "年假提前几天？"), message("assistant", "需要提前7天【来源#1】"));

        assertThat(service.historyForRewrite(history)).containsExactly(
                Map.of("role", "user", "content", "年假提前几天？"),
                Map.of("role", "assistant", "content", "需要提前7天"));
    }

    private ConversationMessage message(String role, String content) {
        return new ConversationMessage(role, content, "2026-09-30T10:00:00", List.of());
    }
}
