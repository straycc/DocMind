package com.yizhaoqi.docmind.service;

import com.yizhaoqi.docmind.client.DeepSeekClient;
import com.yizhaoqi.docmind.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class ConversationQueryRewriter {

    private static final Logger logger = LoggerFactory.getLogger(ConversationQueryRewriter.class);
    private static final Pattern CONTEXT_DEPENDENT = Pattern.compile(
            "(^|.*?)(那|那么|这个|这些|这种|这样|也是|也要|也可以|还是|前面|上面|刚才|它|他们|她们|其|呢)(.*)$");
    private static final Pattern SHORT_ELLIPSIS = Pattern.compile(
            ".*(提前|多少|几天|多久|什么时候|怎么办|怎么处理|是否可以|需要吗|可以吗).*" );

    private final DeepSeekClient deepSeekClient;
    private final AiProperties aiProperties;

    public ConversationQueryRewriter(DeepSeekClient deepSeekClient, AiProperties aiProperties) {
        this.deepSeekClient = deepSeekClient;
        this.aiProperties = aiProperties;
    }

    public RewriteResult rewriteIfNeeded(String currentQuestion, List<Map<String, String>> recentHistory) {
        if (!Boolean.TRUE.equals(aiProperties.getQueryRewrite().getEnabled())
                || recentHistory == null || recentHistory.isEmpty()
                || !requiresContext(currentQuestion)) {
            return RewriteResult.unchanged(currentQuestion);
        }

        long startedAt = System.nanoTime();
        try {
            String rewritten = deepSeekClient.rewriteQuery(
                    currentQuestion,
                    recentHistory,
                    aiProperties.getQueryRewrite().getMaxTokens(),
                    Duration.ofSeconds(aiProperties.getQueryRewrite().getTimeoutSeconds()));
            if (rewritten.isBlank()) {
                return RewriteResult.unchanged(currentQuestion);
            }
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
            logger.info("多轮检索问题改写完成: changed={}, originalLength={}, rewrittenLength={}, elapsedMs={}",
                    !rewritten.equals(currentQuestion), currentQuestion.length(), rewritten.length(), elapsedMillis);
            logger.debug("多轮检索问题改写: original={}, rewritten={}", currentQuestion, rewritten);
            return new RewriteResult(currentQuestion, rewritten, !rewritten.equals(currentQuestion), false,
                    elapsedMillis);
        } catch (Exception error) {
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
            logger.warn("多轮检索问题改写失败，降级使用原问题: elapsedMs={}, reason={}",
                    elapsedMillis, error.getMessage());
            return new RewriteResult(currentQuestion, currentQuestion, false, true, elapsedMillis);
        }
    }

    boolean requiresContext(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String normalized = question.trim();
        return normalized.length() <= 50 && (CONTEXT_DEPENDENT.matcher(normalized).matches()
                || (normalized.length() <= 12 && SHORT_ELLIPSIS.matcher(normalized).matches()));
    }

    public record RewriteResult(
            String originalQuestion,
            String retrievalQuery,
            boolean rewritten,
            boolean fallback,
            long elapsedMillis
    ) {
        static RewriteResult unchanged(String question) {
            return new RewriteResult(question, question, false, false, 0);
        }
    }
}
