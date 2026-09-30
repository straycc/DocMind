package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.config.AiProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConversationQueryRewriterTest {

    @Test
    void rewritesContextDependentFollowUp() {
        DeepSeekClient client = mock(DeepSeekClient.class);
        AiProperties properties = new AiProperties();
        ConversationQueryRewriter rewriter = new ConversationQueryRewriter(client, properties);
        when(client.rewriteQuery(anyString(), any(), anyInt(), any(Duration.class)))
                .thenReturn("试用期员工申请年休假时是否也需要提前7天？");

        ConversationQueryRewriter.RewriteResult result = rewriter.rewriteIfNeeded(
                "试用期员工也是这样吗？",
                List.of(Map.of("role", "user", "content", "年假需要提前几天？")));

        assertThat(result.rewritten()).isTrue();
        assertThat(result.retrievalQuery()).contains("试用期", "年休假", "提前7天");
    }

    @Test
    void skipsIndependentQuestion() {
        DeepSeekClient client = mock(DeepSeekClient.class);
        ConversationQueryRewriter rewriter = new ConversationQueryRewriter(client, new AiProperties());

        ConversationQueryRewriter.RewriteResult result = rewriter.rewriteIfNeeded(
                "员工申请婚假需要提供哪些材料？",
                List.of(Map.of("role", "user", "content", "年假需要提前几天？")));

        assertThat(result.rewritten()).isFalse();
        assertThat(result.retrievalQuery()).isEqualTo("员工申请婚假需要提供哪些材料？");
        verifyNoInteractions(client);
    }

    @Test
    void fallsBackToOriginalQuestionWhenRewriteFails() {
        DeepSeekClient client = mock(DeepSeekClient.class);
        ConversationQueryRewriter rewriter = new ConversationQueryRewriter(client, new AiProperties());
        when(client.rewriteQuery(anyString(), any(), anyInt(), any(Duration.class)))
                .thenThrow(new IllegalStateException("timeout"));

        ConversationQueryRewriter.RewriteResult result = rewriter.rewriteIfNeeded(
                "那需要提前几天？", List.of(Map.of("role", "user", "content", "年假")));

        assertThat(result.fallback()).isTrue();
        assertThat(result.retrievalQuery()).isEqualTo("那需要提前几天？");
    }
}
