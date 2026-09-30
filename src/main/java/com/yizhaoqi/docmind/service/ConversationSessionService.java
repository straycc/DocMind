package com.yizhaoqi.docmind.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.docmind.model.ConversationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 管理用户可见的多个聊天会话；消息正文仍由 ConversationHistoryService 保存。 */
@Service
public class ConversationSessionService {
    private static final Logger logger = LoggerFactory.getLogger(ConversationSessionService.class);
    private static final Duration SESSION_TTL = Duration.ofDays(7);
    private static final int TITLE_MAX_LENGTH = 28;

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final ConversationHistoryService historyService;

    public ConversationSessionService(RedisTemplate<String, String> redisTemplate,
                                      ObjectMapper objectMapper,
                                      ConversationHistoryService historyService) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.historyService = historyService;
    }

    public ConversationSummary create(String userId) {
        long now = System.currentTimeMillis();
        ConversationMeta meta = new ConversationMeta(
                UUID.randomUUID().toString(), userId, "新对话", now, now, false);
        save(meta);
        return toSummary(meta);
    }

    public List<ConversationSummary> list(String userId) {
        migrateLegacyConversation(userId);
        Set<String> ids = redisTemplate.opsForZSet().reverseRange(userIndexKey(userId), 0, 99);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<ConversationSummary> result = new ArrayList<>();
        for (String id : ids) {
            ConversationMeta meta = loadMeta(id);
            if (meta == null || !userId.equals(meta.userId())) {
                redisTemplate.opsForZSet().remove(userIndexKey(userId), id);
                continue;
            }
            result.add(toSummary(meta));
        }
        return result;
    }

    public List<ConversationMessage> messages(String userId, String conversationId) {
        requireOwned(userId, conversationId);
        return historyService.load(conversationId);
    }

    public String requireOwned(String userId, String conversationId) {
        ConversationMeta meta = loadMeta(conversationId);
        if (meta == null || !userId.equals(meta.userId())) {
            throw new IllegalArgumentException("会话不存在或无权访问");
        }
        return conversationId;
    }

    public void touchWithMessage(String userId, String conversationId, String userMessage) {
        ConversationMeta current = loadMeta(conversationId);
        if (current == null || !userId.equals(current.userId())) {
            throw new IllegalArgumentException("会话不存在或无权访问");
        }
        long now = System.currentTimeMillis();
        String title = current.titled() ? current.title() : buildTitle(userMessage);
        save(new ConversationMeta(current.conversationId(), current.userId(), title,
                current.createdAt(), now, true));
    }

    public void delete(String userId, String conversationId) {
        requireOwned(userId, conversationId);
        redisTemplate.delete(metaKey(conversationId));
        redisTemplate.delete(historyKey(conversationId));
        redisTemplate.opsForZSet().remove(userIndexKey(userId), conversationId);
    }

    private void migrateLegacyConversation(String userId) {
        String legacyId = redisTemplate.opsForValue().get("user:" + userId + ":current_conversation");
        if (legacyId == null || loadMeta(legacyId) != null) return;
        List<ConversationMessage> history = historyService.load(legacyId);
        if (history.isEmpty()) return;
        String firstQuestion = history.stream()
                .filter(message -> "user".equals(message.role()))
                .map(ConversationMessage::content)
                .findFirst().orElse("历史对话");
        long now = System.currentTimeMillis();
        save(new ConversationMeta(legacyId, userId, buildTitle(firstQuestion), now, now, true));
    }

    private void save(ConversationMeta meta) {
        try {
            redisTemplate.opsForValue().set(metaKey(meta.conversationId()),
                    objectMapper.writeValueAsString(meta), SESSION_TTL);
            redisTemplate.opsForZSet().add(userIndexKey(meta.userId()), meta.conversationId(), meta.updatedAt());
            redisTemplate.expire(userIndexKey(meta.userId()), SESSION_TTL);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("保存会话元数据失败", error);
        }
    }

    private ConversationMeta loadMeta(String conversationId) {
        String json = redisTemplate.opsForValue().get(metaKey(conversationId));
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, ConversationMeta.class);
        } catch (JsonProcessingException error) {
            logger.error("解析会话元数据失败: conversationId={}", conversationId, error);
            return null;
        }
    }

    private String buildTitle(String message) {
        String normalized = message == null ? "新对话" : message.trim().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) return "新对话";
        return normalized.length() <= TITLE_MAX_LENGTH
                ? normalized : normalized.substring(0, TITLE_MAX_LENGTH) + "…";
    }

    private ConversationSummary toSummary(ConversationMeta meta) {
        return new ConversationSummary(meta.conversationId(), meta.title(),
                Instant.ofEpochMilli(meta.createdAt()).toString(),
                Instant.ofEpochMilli(meta.updatedAt()).toString());
    }

    private String userIndexKey(String userId) { return "chat:user:" + userId + ":conversations"; }
    private String metaKey(String conversationId) { return "chat:conversation:" + conversationId + ":meta"; }
    private String historyKey(String conversationId) { return "conversation:" + conversationId; }

    private record ConversationMeta(String conversationId, String userId, String title,
                                    long createdAt, long updatedAt, boolean titled) { }

    public record ConversationSummary(String conversationId, String title,
                                      String createdAt, String updatedAt) { }
}
