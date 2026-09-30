package com.yizhaoqi.smartpai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 嵌入向量生成客户端
@Component
public class EmbeddingClient {

    @Value("${embedding.api.model}")
    private String modelId;
    
    @Value("${embedding.api.batch-size:100}")
    private int batchSize;

    @Value("${embedding.api.dimension:2048}")
    private int dimension;

    @Value("${embedding.api.operation-timeout-seconds:150}")
    private long operationTimeoutSeconds;
    
    private static final Logger logger = LoggerFactory.getLogger(EmbeddingClient.class);
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public EmbeddingClient(WebClient embeddingWebClient, ObjectMapper objectMapper) {
        this.webClient = embeddingWebClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 调用通义千问 API 生成向量
     * @param texts 输入文本列表
     * @return 对应的向量列表
     */
    public List<float[]> embed(List<String> texts) {
        try {
            logger.info("开始生成向量，文本数量: {}", texts.size());
            
            List<float[]> all = new ArrayList<>(texts.size());
            int totalBatches = (texts.size() + batchSize - 1) / batchSize;
            int batchNumber = 0;
            for (int start = 0; start < texts.size(); start += batchSize) {
                batchNumber++;
                int end = Math.min(start + batchSize, texts.size());
                List<String> sub = texts.subList(start, end);
                logger.debug("调用向量 API, 批次: {}-{} (size={})", start, end - 1, sub.size());
                String response = callApiOnce(sub);
                all.addAll(parseVectors(response));

                if (batchNumber % 50 == 0 || batchNumber == totalBatches) {
                    int progress = end * 100 / texts.size();
                    logger.info("向量生成进度：批次 {}/{}, 文本 {}/{} ({}%)",
                            batchNumber, totalBatches, end, texts.size(), progress);
                }
            }
            logger.info("成功生成向量，总数量: {}", all.size());
            return all;
        } catch (Exception e) {
            logger.error("调用向量化 API 失败: {}", e.getMessage(), e);
            throw new RuntimeException("向量生成失败", e);
        }
    }

    public String getModelId() {
        return modelId;
    }

    public int getDimension() {
        return dimension;
    }

    private String callApiOnce(List<String> batch) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", modelId);
        requestBody.put("input", batch);
        requestBody.put("dimension", dimension);  // 直接在根级别设置dimension
        requestBody.put("encoding_format", "float");  // 添加编码格式

        return webClient.post()
                .uri("/embeddings")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                // 在 API 调用层消化连接重置、超时、限流和服务端短暂故障，
                // 避免把一次网络抖动放大成 Kafka 整份文档重跑。
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(5))
                        .jitter(0.2)
                        .filter(this::isTransientFailure)
                        .doBeforeRetry(signal -> logger.warn(
                                "向量API短暂异常，准备第{}次重试: {}",
                                signal.totalRetries() + 1, signal.failure().getMessage()))
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                // 同步调用
                .block(Duration.ofSeconds(operationTimeoutSeconds));
    }

    boolean isTransientFailure(Throwable error) {
        if (error instanceof WebClientRequestException) {
            return true;
        }
        if (error instanceof WebClientResponseException responseException) {
            int status = responseException.getStatusCode().value();
            return status == 429 || status >= 500;
        }
        return false;
    }

    private List<float[]> parseVectors(String response) throws Exception {
        JsonNode jsonNode = objectMapper.readTree(response);
        JsonNode data = jsonNode.get("data");  // 兼容模式下使用data字段
        if (data == null || !data.isArray()) {
            throw new RuntimeException("API 响应格式错误: data 字段不存在或不是数组");
        }
        
        List<float[]> vectors = new ArrayList<>();
        for (JsonNode item : data) {
            JsonNode embedding = item.get("embedding");
            if (embedding != null && embedding.isArray()) {
                float[] vector = new float[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    vector[i] = (float) embedding.get(i).asDouble();
                }
                vectors.add(vector);
            }
        }
        return vectors;
    }
}
