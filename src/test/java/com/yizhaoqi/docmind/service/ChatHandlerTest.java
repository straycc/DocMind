package com.yizhaoqi.docmind.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.docmind.client.DeepSeekClient;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.socket.WebSocketSession;
import reactor.core.Disposable;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatHandlerTest {

    @Test
    void stopDisposesTheActiveModelRequest() {
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("user:admin:current_conversation")).thenReturn("conversation-1");

        HybridSearchService search = mock(HybridSearchService.class);
        when(search.searchWithPermission(anyString(), eq("admin"), eq(5))).thenReturn(List.of());
        ConversationHistoryService history = mock(ConversationHistoryService.class);
        when(history.load("conversation-1")).thenReturn(List.of());
        when(history.historyForRewrite(any())).thenReturn(List.of());
        when(history.historyForAnswer(any())).thenReturn(List.of());
        ConversationQueryRewriter rewriter = mock(ConversationQueryRewriter.class);
        when(rewriter.rewriteIfNeeded("问题", List.of())).thenReturn(
                new ConversationQueryRewriter.RewriteResult("问题", "问题", false, false, 0));

        Disposable disposable = mock(Disposable.class);
        DeepSeekClient deepSeek = mock(DeepSeekClient.class);
        when(deepSeek.streamResponse(anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(disposable);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-1");
        when(session.isOpen()).thenReturn(true);

        ChatHandler handler = new ChatHandler(redis, search, deepSeek, history, rewriter, new ObjectMapper());
        handler.processMessage("admin", "问题", session);
        handler.stopResponse("admin", session);

        verify(disposable).dispose();
    }
}
