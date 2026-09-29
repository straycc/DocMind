package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.entity.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天处理服务，负责 WebSocket 流式回答、检索来源和短期对话历史。
 */
@Service
public class ChatHandler {

    private static final Logger logger = LoggerFactory.getLogger(ChatHandler.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final HybridSearchService searchService;
    private final DeepSeekClient deepSeekClient;
    private final ObjectMapper objectMapper;

    /** 每个 WebSocket 会话正在生成的完整回答。 */
    private final Map<String, StringBuilder> responseBuilders = new ConcurrentHashMap<>();
    /** 仅用于保证正常完成与异常完成只执行一次。 */
    private final Map<String, CompletableFuture<String>> responseFutures = new ConcurrentHashMap<>();
    private final Map<String, Boolean> stopFlags = new ConcurrentHashMap<>();

    public ChatHandler(RedisTemplate<String, String> redisTemplate,
                       HybridSearchService searchService,
                       DeepSeekClient deepSeekClient) {
        this.redisTemplate = redisTemplate;
        this.searchService = searchService;
        this.deepSeekClient = deepSeekClient;
        this.objectMapper = new ObjectMapper();
    }

    public void processMessage(String userId, String userMessage, WebSocketSession session) {
        String sessionId = session.getId();
        logger.info("开始处理消息，用户ID: {}, 会话ID: {}", userId, sessionId);
        try {
            // 同一连接开始新问题时，解除上一轮停止标志。
            stopFlags.remove(sessionId);
            String conversationId = getOrCreateConversationId(userId);
            List<Map<String, String>> history = getConversationHistory(conversationId);
            List<SearchResult> searchResults = searchService.searchWithPermission(userMessage, userId, 5);
            RagAnswerContextBuilder.Context answerContext = RagAnswerContextBuilder.build(searchResults);

            responseBuilders.put(sessionId, new StringBuilder());
            CompletableFuture<String> responseFuture = new CompletableFuture<>();
            responseFutures.put(sessionId, responseFuture);

            logger.info("开始生成回答：会话ID={}, 来源数={}", sessionId, answerContext.sources().size());
            deepSeekClient.streamResponse(
                    userMessage,
                    answerContext.promptContext(),
                    history,
                    chunk -> appendAndSendChunk(session, chunk),
                    error -> failResponse(session, responseFuture, error),
                    () -> completeResponse(session, responseFuture, conversationId, userMessage, answerContext)
            );
        } catch (Exception error) {
            CompletableFuture<String> future = responseFutures.get(sessionId);
            if (future == null) {
                future = new CompletableFuture<>();
            }
            failResponse(session, future, error);
        }
    }

    private void appendAndSendChunk(WebSocketSession session, String chunk) {
        StringBuilder responseBuilder = responseBuilders.get(session.getId());
        if (responseBuilder != null) {
            responseBuilder.append(chunk);
        }
        sendResponseChunk(session, chunk);
    }

    private void completeResponse(WebSocketSession session,
                                  CompletableFuture<String> responseFuture,
                                  String conversationId,
                                  String userMessage,
                                  RagAnswerContextBuilder.Context answerContext) {
        String sessionId = session.getId();
        StringBuilder responseBuilder = responseBuilders.get(sessionId);
        String completeResponse = responseBuilder == null ? "" : responseBuilder.toString();

        if (!responseFuture.complete(completeResponse)) {
            return;
        }

        RagCitationValidator.Result citationValidation =
                RagCitationValidator.validate(completeResponse, answerContext.sources());
        if (!citationValidation.allCitationIdsValid()) {
            logger.warn("模型返回了不存在的来源编号：会话ID={}, 无效编号={}",
                    sessionId, citationValidation.invalidCitationIds());
        }

        sendCompletionNotification(session, answerContext.sources(), citationValidation);
        updateConversationHistory(conversationId, userMessage, completeResponse);
        cleanupResponse(sessionId, responseFuture);
        logger.info("回答生成完成：会话ID={}, 长度={}, 引用={}, 无效引用={}",
                sessionId,
                completeResponse.length(),
                citationValidation.citedSourceIds(),
                citationValidation.invalidCitationIds());
    }

    private void failResponse(WebSocketSession session, CompletableFuture<String> responseFuture, Throwable error) {
        if (!responseFuture.completeExceptionally(error)) {
            return;
        }
        handleError(session, error);
        sendFailureNotification(session);
        cleanupResponse(session.getId(), responseFuture);
    }

    private void cleanupResponse(String sessionId, CompletableFuture<String> responseFuture) {
        responseBuilders.remove(sessionId);
        responseFutures.remove(sessionId, responseFuture);
        stopFlags.remove(sessionId);
    }

    private String getOrCreateConversationId(String userId) {
        String key = "user:" + userId + ":current_conversation";
        String conversationId = redisTemplate.opsForValue().get(key);

        if (conversationId == null) {
            conversationId = UUID.randomUUID().toString();
            redisTemplate.opsForValue().set(key, conversationId, Duration.ofDays(7));
            logger.info("为用户 {} 创建新的会话ID: {}", userId, conversationId);
        }
        return conversationId;
    }

    private List<Map<String, String>> getConversationHistory(String conversationId) {
        String json = redisTemplate.opsForValue().get("conversation:" + conversationId);
        try {
            if (json == null) {
                return new ArrayList<>();
            }
            return objectMapper.readValue(json, new TypeReference<List<Map<String, String>>>() {
            });
        } catch (JsonProcessingException error) {
            logger.error("解析对话历史失败，会话ID={}", conversationId, error);
            return new ArrayList<>();
        }
    }

    private void updateConversationHistory(String conversationId, String userMessage, String response) {
        String key = "conversation:" + conversationId;
        List<Map<String, String>> history = getConversationHistory(conversationId);
        String timestamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));

        history.add(message("user", userMessage, timestamp));
        history.add(message("assistant", response, timestamp));
        if (history.size() > 20) {
            history = new ArrayList<>(history.subList(history.size() - 20, history.size()));
        }

        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(history), Duration.ofDays(7));
        } catch (JsonProcessingException error) {
            logger.error("序列化对话历史失败，会话ID={}", conversationId, error);
        }
    }

    private Map<String, String> message(String role, String content, String timestamp) {
        Map<String, String> message = new HashMap<>();
        message.put("role", role);
        message.put("content", content);
        message.put("timestamp", timestamp);
        return message;
    }

    private void sendResponseChunk(WebSocketSession session, String chunk) {
        if (Boolean.TRUE.equals(stopFlags.get(session.getId()))) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of("chunk", chunk))));
        } catch (Exception error) {
            logger.error("发送响应块失败，会话ID={}", session.getId(), error);
        }
    }

    /** 回答结束时将来源映射和引用编号校验结果一起发送给前端。 */
    private void sendCompletionNotification(WebSocketSession session,
                                            List<RagAnswerContextBuilder.Source> sources,
                                            RagCitationValidator.Result citationValidation) {
        try {
            Map<String, Object> notification = Map.of(
                    "type", "completion",
                    "status", "finished",
                    "message", "响应已完成",
                    "timestamp", System.currentTimeMillis(),
                    "date", java.time.LocalDateTime.now().toString(),
                    "sources", sources,
                    "citationValidation", citationValidation
            );
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(notification)));
        } catch (Exception error) {
            logger.error("发送完成通知失败，会话ID={}", session.getId(), error);
        }
    }

    private void sendFailureNotification(WebSocketSession session) {
        try {
            Map<String, Object> notification = Map.of(
                    "type", "completion",
                    "status", "failed",
                    "message", "响应生成失败",
                    "timestamp", System.currentTimeMillis(),
                    "date", java.time.LocalDateTime.now().toString(),
                    "sources", List.of()
            );
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(notification)));
        } catch (Exception notificationError) {
            logger.error("发送失败通知失败，会话ID={}", session.getId(), notificationError);
        }
    }

    private void handleError(WebSocketSession session, Throwable error) {
        logger.error("AI 服务错误，会话ID={}", session.getId(), error);
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(
                    Map.of("error", "AI服务暂时不可用，请稍后重试"))));
        } catch (Exception sendError) {
            logger.error("发送错误消息失败，会话ID={}", session.getId(), sendError);
        }
    }

    /** 停止按钮只停止向前端继续推送；当前模型请求结束后仍会清理本轮资源。 */
    public void stopResponse(String userId, WebSocketSession session) {
        String sessionId = session.getId();
        logger.info("收到停止请求，用户ID: {}, 会话ID: {}", userId, sessionId);
        stopFlags.put(sessionId, true);
        try {
            Map<String, Object> response = Map.of(
                    "type", "stop",
                    "message", "响应已停止",
                    "timestamp", System.currentTimeMillis(),
                    "date", java.time.Instant.now().toString()
            );
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
        } catch (Exception error) {
            logger.error("发送停止确认失败，会话ID={}", sessionId, error);
        }
    }
}
