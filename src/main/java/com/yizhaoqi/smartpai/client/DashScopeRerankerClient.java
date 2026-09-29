package com.yizhaoqi.smartpai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 百炼 qwen3-rerank 的原生 DashScope API 客户端。 */
@Component
@ConditionalOnProperty(prefix = "rerank.api", name = "enabled", havingValue = "true")
public class DashScopeRerankerClient implements RerankerClient {
    private static final Logger logger = LoggerFactory.getLogger(DashScopeRerankerClient.class);

    private final String apiUrl;
    private final String apiKey;
    private final String model;
    private final ObjectMapper objectMapper;
    private WebClient webClient;

    public DashScopeRerankerClient(
            ObjectMapper objectMapper,
            @Value("${rerank.api.url:https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank}") String apiUrl,
            @Value("${rerank.api.key:}") String apiKey,
            @Value("${rerank.api.model:qwen3-rerank}") String model) {
        this.objectMapper = objectMapper;
        this.apiUrl = trimTrailingSlash(apiUrl);
        this.apiKey = apiKey;
        this.model = model;
    }

    @PostConstruct
    void initialize() {
        if (apiUrl.isBlank()) {
            throw new IllegalStateException("已启用 rerank，但未配置 RERANK_API_URL");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("已启用 rerank，但未配置 RERANK_API_KEY 或 EMBEDDING_API_KEY");
        }
        webClient = WebClient.builder()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public List<RankedDocument> rerank(String query, List<String> documents, int topN) {
        if (documents == null || documents.isEmpty() || topN <= 0) {
            return List.of();
        }
        int actualTopN = Math.min(topN, documents.size());
        try {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("query", query);
            input.put("documents", documents);

            Map<String, Object> parameters = new LinkedHashMap<>();
            // 仅需要下标和相关性分数，避免将候选正文重复返回。
            parameters.put("return_documents", false);
            parameters.put("top_n", actualTopN);

            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", model);
            request.put("input", input);
            request.put("parameters", parameters);

            String response = webClient.post()
                    .uri(apiUrl)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .retryWhen(Retry.backoff(4, Duration.ofSeconds(1))
                            .maxBackoff(Duration.ofSeconds(10))
                            .jitter(0.2)
                            .filter(this::isTransientFailure))
                    .block(Duration.ofSeconds(45));
            return parseResponse(response, documents.size(), actualTopN);
        } catch (Exception exception) {
            throw new IllegalStateException("调用百炼重排序 API 失败: " + exception.getMessage(), exception);
        }
    }

    private List<RankedDocument> parseResponse(String response, int documentCount, int expectedTopN) throws Exception {
        JsonNode root = objectMapper.readTree(response);
        JsonNode results = root.path("output").path("results");
        if (!results.isArray()) {
            throw new IllegalStateException("百炼重排序响应缺少 output.results: "
                    + root.path("message").asText(response));
        }
        Map<Integer, RankedDocument> distinct = new LinkedHashMap<>();
        for (JsonNode item : results) {
            int index = item.path("index").asInt(-1);
            if (index < 0 || index >= documentCount) {
                throw new IllegalStateException("百炼重排序返回了非法候选下标: " + index);
            }
            distinct.putIfAbsent(index, new RankedDocument(index, item.path("relevance_score").asDouble()));
        }
        if (distinct.size() < expectedTopN) {
            throw new IllegalStateException("百炼重排序返回数量不足，期望=" + expectedTopN + "，实际=" + distinct.size());
        }
        List<RankedDocument> ranked = new ArrayList<>(distinct.values());
        ranked.sort(Comparator.comparingDouble(RankedDocument::relevanceScore).reversed());
        logger.debug("百炼重排序完成：model={}, candidates={}, returned={}", model, documentCount, ranked.size());
        return ranked.subList(0, expectedTopN);
    }

    private boolean isTransientFailure(Throwable throwable) {
        return throwable instanceof WebClientRequestException
                || throwable instanceof WebClientResponseException.ServiceUnavailable
                || throwable instanceof WebClientResponseException.GatewayTimeout
                || throwable instanceof WebClientResponseException.TooManyRequests;
    }

    private String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceFirst("/+$", "");
    }
}
