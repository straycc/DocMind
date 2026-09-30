package com.yizhaoqi.docmind.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.docmind.client.DeepSeekClient;
import com.yizhaoqi.docmind.entity.SearchResult;
import com.yizhaoqi.docmind.model.ConversationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import reactor.core.Disposable;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 负责多轮检索、WebSocket 流式回答、引用与请求生命周期。 */
@Service
public class ChatHandler {

    private static final Logger logger = LoggerFactory.getLogger(ChatHandler.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final HybridSearchService searchService;
    private final DeepSeekClient deepSeekClient;
    private final ConversationHistoryService historyService;
    private final ConversationQueryRewriter queryRewriter;
    private final ObjectMapper objectMapper;

    /** 同一个 WebSocket 连接最多保留一个活动回答，键为 sessionId。 */
    private final Map<String, ChatTurn> activeTurns = new ConcurrentHashMap<>();

    public ChatHandler(RedisTemplate<String, String> redisTemplate,
                       HybridSearchService searchService,
                       DeepSeekClient deepSeekClient,
                       ConversationHistoryService historyService,
                       ConversationQueryRewriter queryRewriter,
                       ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.searchService = searchService;
        this.deepSeekClient = deepSeekClient;
        this.historyService = historyService;
        this.queryRewriter = queryRewriter;
        this.objectMapper = objectMapper;
    }

    public void processMessage(String userId, String userMessage, WebSocketSession session) {
        String conversationId = getOrCreateConversationId(userId);
        ChatTurn turn = new ChatTurn(session.getId(), conversationId, UUID.randomUUID().toString(), userMessage);
        ChatTurn previous = activeTurns.put(session.getId(), turn);
        if (previous != null) {
            cancelTurn(previous, session, "superseded", false);
        }

        logger.info("开始处理消息: userId={}, conversationId={}, turnId={}",
                userId, conversationId, turn.turnId);
        sendEvent(session, Map.of(
                "type", "start",
                "conversationId", conversationId,
                "turnId", turn.turnId,
                "timestamp", System.currentTimeMillis()));

        try {
            List<ConversationMessage> storedHistory = historyService.load(conversationId);
            List<Map<String, String>> rewriteHistory = historyService.historyForRewrite(storedHistory);
            ConversationQueryRewriter.RewriteResult rewrite =
                    queryRewriter.rewriteIfNeeded(userMessage, rewriteHistory);
            turn.rewriteResult = rewrite;
            if (!isActive(turn)) {
                return;
            }

            List<SearchResult> searchResults =
                    searchService.searchWithPermission(rewrite.retrievalQuery(), userId, 5);
            RagAnswerContextBuilder.Context answerContext = RagAnswerContextBuilder.build(searchResults);
            turn.answerContext = answerContext;
            List<Map<String, String>> answerHistory = historyService.historyForAnswer(storedHistory);

            logger.info("开始生成回答: conversationId={}, turnId={}, sources={}, queryRewritten={}",
                    conversationId, turn.turnId, answerContext.sources().size(), rewrite.rewritten());
            Disposable subscription = deepSeekClient.streamResponse(
                    userMessage,
                    answerContext.promptContext(),
                    answerHistory,
                    chunk -> appendAndSendChunk(session, turn, chunk),
                    error -> failResponse(session, turn, error),
                    () -> completeResponse(session, turn)
            );
            turn.setSubscription(subscription);
        } catch (Exception error) {
            failResponse(session, turn, error);
        }
    }

    private void appendAndSendChunk(WebSocketSession session, ChatTurn turn, String chunk) {
        if (!isActive(turn) || turn.terminal.get()) {
            return;
        }
        synchronized (turn.response) {
            turn.response.append(chunk);
        }
        sendEvent(session, Map.of(
                "type", "chunk",
                "conversationId", turn.conversationId,
                "turnId", turn.turnId,
                "chunk", chunk));
    }

    private void completeResponse(WebSocketSession session, ChatTurn turn) {
        if (!isActive(turn) || !turn.terminal.compareAndSet(false, true)) {
            return;
        }
        String completeResponse;
        synchronized (turn.response) {
            completeResponse = turn.response.toString();
        }
        RagCitationValidator.Result validation =
                RagCitationValidator.validate(completeResponse, turn.answerContext.sources());
        if (!validation.allCitationIdsValid()) {
            logger.warn("模型返回不存在的来源编号: conversationId={}, turnId={}, invalid={}",
                    turn.conversationId, turn.turnId, validation.invalidCitationIds());
        }
        if (!turn.answerContext.sources().isEmpty() && !validation.hasCitation()) {
            logger.warn("回答未引用任何本轮来源: conversationId={}, turnId={}",
                    turn.conversationId, turn.turnId);
        }

        historyService.appendTurn(turn.conversationId, turn.userMessage,
                completeResponse, turn.answerContext.sources());
        sendEvent(session, Map.of(
                "type", "completion",
                "status", "finished",
                "message", "响应已完成",
                "conversationId", turn.conversationId,
                "turnId", turn.turnId,
                "timestamp", System.currentTimeMillis(),
                "sources", turn.answerContext.sources(),
                "citationValidation", validation,
                "queryRewritten", turn.rewriteResult.rewritten()));
        activeTurns.remove(turn.sessionId, turn);
        logger.info("回答生成完成: conversationId={}, turnId={}, length={}, citations={}, invalid={}",
                turn.conversationId, turn.turnId, completeResponse.length(),
                validation.citedSourceIds(), validation.invalidCitationIds());
    }

    private void failResponse(WebSocketSession session, ChatTurn turn, Throwable error) {
        if (!isActive(turn) || !turn.terminal.compareAndSet(false, true)) {
            return;
        }
        activeTurns.remove(turn.sessionId, turn);
        logger.error("AI服务错误: conversationId={}, turnId={}", turn.conversationId, turn.turnId, error);
        sendEvent(session, Map.of(
                "type", "error",
                "conversationId", turn.conversationId,
                "turnId", turn.turnId,
                "error", "AI服务暂时不可用，请稍后重试"));
        sendEvent(session, Map.of(
                "type", "completion",
                "status", "failed",
                "message", "响应生成失败",
                "conversationId", turn.conversationId,
                "turnId", turn.turnId,
                "timestamp", System.currentTimeMillis(),
                "sources", List.of()));
    }

    public void stopResponse(String userId, WebSocketSession session) {
        stopResponse(userId, session, null);
    }

    /** turnId 为空时停止当前活动回答；传入时只停止对应轮次。 */
    public void stopResponse(String userId, WebSocketSession session, String turnId) {
        ChatTurn turn = activeTurns.get(session.getId());
        if (turn == null || (turnId != null && !turnId.equals(turn.turnId))) {
            logger.info("忽略无活动轮次的停止请求: userId={}, sessionId={}, turnId={}",
                    userId, session.getId(), turnId);
            return;
        }
        logger.info("停止回答: userId={}, conversationId={}, turnId={}",
                userId, turn.conversationId, turn.turnId);
        cancelTurn(turn, session, "user_cancelled", true);
    }

    /** WebSocket 断开时取消仍在进行的模型 HTTP 请求。 */
    public void connectionClosed(WebSocketSession session) {
        ChatTurn turn = activeTurns.get(session.getId());
        if (turn != null) {
            cancelTurn(turn, session, "connection_closed", false);
        }
    }

    private void cancelTurn(ChatTurn turn, WebSocketSession session, String reason, boolean notifyClient) {
        if (!turn.terminal.compareAndSet(false, true)) {
            return;
        }
        turn.disposeSubscription();
        activeTurns.remove(turn.sessionId, turn);
        if (notifyClient && session.isOpen()) {
            sendEvent(session, Map.of(
                    "type", "stop",
                    "status", "stopped",
                    "reason", reason,
                    "conversationId", turn.conversationId,
                    "turnId", turn.turnId,
                    "message", "响应已停止",
                    "timestamp", System.currentTimeMillis()));
        }
    }

    private boolean isActive(ChatTurn turn) {
        return activeTurns.get(turn.sessionId) == turn;
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

    private void sendEvent(WebSocketSession session, Map<String, ?> event) {
        if (!session.isOpen()) {
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(event);
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(payload));
                }
            }
        } catch (Exception error) {
            logger.error("发送WebSocket消息失败: sessionId={}", session.getId(), error);
        }
    }

    private static final class ChatTurn {
        private final String sessionId;
        private final String conversationId;
        private final String turnId;
        private final String userMessage;
        private final StringBuilder response = new StringBuilder();
        private final AtomicBoolean terminal = new AtomicBoolean(false);
        private volatile Disposable subscription;
        private volatile RagAnswerContextBuilder.Context answerContext =
                new RagAnswerContextBuilder.Context("", List.of());
        private volatile ConversationQueryRewriter.RewriteResult rewriteResult =
                new ConversationQueryRewriter.RewriteResult("", "", false, false, 0);

        private ChatTurn(String sessionId, String conversationId, String turnId, String userMessage) {
            this.sessionId = sessionId;
            this.conversationId = conversationId;
            this.turnId = turnId;
            this.userMessage = userMessage;
        }

        private void setSubscription(Disposable subscription) {
            this.subscription = subscription;
            if (terminal.get() && subscription != null && !subscription.isDisposed()) {
                subscription.dispose();
            }
        }

        private void disposeSubscription() {
            Disposable current = subscription;
            if (current != null && !current.isDisposed()) {
                current.dispose();
            }
        }
    }
}
