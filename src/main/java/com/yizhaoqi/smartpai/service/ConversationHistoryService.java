package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.model.ConversationMessage;
import com.yizhaoqi.smartpai.model.ConversationSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class ConversationHistoryService {

    private static final Logger logger = LoggerFactory.getLogger(ConversationHistoryService.class);
    private static final Pattern HISTORICAL_CITATION =
            Pattern.compile("【(?:来源#\\s*|S)(\\d+)】");
    private static final Duration HISTORY_TTL = Duration.ofDays(7);

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final AiProperties aiProperties;

    public ConversationHistoryService(RedisTemplate<String, String> redisTemplate,
                                      ObjectMapper objectMapper,
                                      AiProperties aiProperties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.aiProperties = aiProperties;
    }

    public List<ConversationMessage> load(String conversationId) {
        String json = redisTemplate.opsForValue().get(key(conversationId));
        if (json == null) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(objectMapper.readValue(json,
                    new TypeReference<List<ConversationMessage>>() { }));
        } catch (JsonProcessingException error) {
            logger.error("解析对话历史失败，会话ID={}", conversationId, error);
            return new ArrayList<>();
        }
    }

    public void appendTurn(String conversationId,
                           String userMessage,
                           String assistantMessage,
                           List<RagAnswerContextBuilder.Source> sources) {
        List<ConversationMessage> history = load(conversationId);
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        history.add(new ConversationMessage("user", userMessage, timestamp, List.of()));
        history.add(new ConversationMessage("assistant", assistantMessage, timestamp,
                toConversationSources(sources)));

        int maxStored = Math.max(2, aiProperties.getHistory().getMaxStoredMessages());
        if (history.size() > maxStored) {
            history = new ArrayList<>(history.subList(history.size() - maxStored, history.size()));
        }
        try {
            redisTemplate.opsForValue().set(key(conversationId),
                    objectMapper.writeValueAsString(history), HISTORY_TTL);
        } catch (JsonProcessingException error) {
            logger.error("序列化对话历史失败，会话ID={}", conversationId, error);
        }
    }

    /** 回答模型使用的历史：清除旧引用，并按近似 token 预算从最新消息向前截取。 */
    public List<Map<String, String>> historyForAnswer(List<ConversationMessage> history) {
        int budget = Math.max(0, aiProperties.getHistory().getMaxTokens());
        List<Map<String, String>> selected = new ArrayList<>();
        int used = 0;
        for (int index = history.size() - 1; index >= 0; index--) {
            ConversationMessage message = history.get(index);
            if (!validRole(message.role()) || message.content() == null) {
                continue;
            }
            String sanitized = sanitizeHistoricalCitations(message.content()).trim();
            if (sanitized.isEmpty()) {
                continue;
            }
            int estimated = estimateTokens(sanitized);
            if (used + estimated > budget) {
                break;
            }
            selected.add(Map.of("role", message.role(), "content", sanitized));
            used += estimated;
        }
        Collections.reverse(selected);
        return selected;
    }

    /** 问题改写只看最近若干轮，且不携带旧来源编号和来源正文。 */
    public List<Map<String, String>> historyForRewrite(List<ConversationMessage> history) {
        int messageLimit = Math.max(1, aiProperties.getQueryRewrite().getRecentTurns()) * 2;
        List<Map<String, String>> result = new ArrayList<>();
        for (int index = Math.max(0, history.size() - messageLimit); index < history.size(); index++) {
            ConversationMessage message = history.get(index);
            if (validRole(message.role()) && message.content() != null) {
                String content = sanitizeHistoricalCitations(message.content()).trim();
                if (!content.isEmpty()) {
                    result.add(Map.of("role", message.role(), "content", content));
                }
            }
        }
        return result;
    }

    String sanitizeHistoricalCitations(String content) {
        return HISTORICAL_CITATION.matcher(content == null ? "" : content).replaceAll("");
    }

    int estimateTokens(String text) {
        int tokens = 0;
        int nonCjkCharacters = 0;
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (Character.UnicodeScript.of(character) == Character.UnicodeScript.HAN) {
                tokens++;
            } else if (!Character.isWhitespace(character)) {
                nonCjkCharacters++;
            }
        }
        return tokens + Math.max(1, (nonCjkCharacters + 3) / 4) + 4;
    }

    private boolean validRole(String role) {
        return "user".equals(role) || "assistant".equals(role);
    }

    private List<ConversationSource> toConversationSources(List<RagAnswerContextBuilder.Source> sources) {
        if (sources == null) {
            return List.of();
        }
        return sources.stream().map(source -> new ConversationSource(
                source.sourceId(), source.fileUploadId(), source.chunkOrdinal(), source.fileName(),
                source.titlePath(), source.pageStart(), source.pageEnd(), source.sourceLabel(),
                source.excerpt())).toList();
    }

    private String key(String conversationId) {
        return "conversation:" + conversationId;
    }
}
