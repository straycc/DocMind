package com.yizhaoqi.smartpai.client;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.yizhaoqi.smartpai.config.AiProperties;

@Service
public class DeepSeekClient {

    private final WebClient webClient;
    private final String apiKey;
    private final String model;
    private final AiProperties aiProperties;
    private static final Logger logger = LoggerFactory.getLogger(DeepSeekClient.class);
    
    public DeepSeekClient(@Value("${deepseek.api.url}") String apiUrl,
                         @Value("${deepseek.api.key}") String apiKey,
                         @Value("${deepseek.api.model}") String model,
                         AiProperties aiProperties) {
        WebClient.Builder builder = WebClient.builder().baseUrl(apiUrl);
        
        // 只有当 API key 不为空时才添加 Authorization header
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        
        this.webClient = builder.build();
        this.apiKey = apiKey;
        this.model = model;
        this.aiProperties = aiProperties;
    }

    // 流式响应
    public void streamResponse(String userMessage, 
                             String context,
                             List<Map<String, String>> history,
                             Consumer<String> onChunk,
                             Consumer<Throwable> onError,
                             Runnable onComplete) {
        
        Map<String, Object> request = buildRequest(userMessage, context, history);
        
        webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .subscribe(
                    chunk -> processChunk(chunk, onChunk),
                    onError,
                    onComplete
                );
    }

    // 构建请求
    private Map<String, Object> buildRequest(String userMessage, 
                                           String context,
                                           List<Map<String, String>> history) {
        logger.info("构建请求，用户消息：{}，上下文长度：{}，历史消息数：{}", 
                   userMessage, 
                   context != null ? context.length() : 0, 
                   history != null ? history.size() : 0);
        
        Map<String, Object> request = new java.util.HashMap<>();
        request.put("model", model);
        request.put("messages", buildMessages(userMessage, context, history));
        request.put("stream", true);
        // 生成参数
        AiProperties.Generation gen = aiProperties.getGeneration();
        if (gen.getTemperature() != null) {
            request.put("temperature", gen.getTemperature());
        }
        if (gen.getTopP() != null) {
            request.put("top_p", gen.getTopP());
        }
        if (gen.getMaxTokens() != null) {
            request.put("max_tokens", gen.getMaxTokens());
        }
        return request;
    }

    // 构建消息
    private List<Map<String, String>> buildMessages(String userMessage,
                                                  String context,
                                                  List<Map<String, String>> history) {
        List<Map<String, String>> messages = new ArrayList<>();

        AiProperties.Prompt promptCfg = aiProperties.getPrompt();

        // 1. system 仅放不可被文档覆盖的规则，检索正文不能混入 system 指令。
        StringBuilder sysBuilder = new StringBuilder();
        String rules = promptCfg.getRules();
        if (rules != null && !rules.isBlank()) {
            sysBuilder.append(rules).append("\n\n");
        }

        String systemContent = sysBuilder.toString();
        messages.add(Map.of(
            "role", "system",
            "content", systemContent
        ));
        logger.debug("添加了系统消息，长度: {}", systemContent.length());

        // 2. 追加历史消息（只保留 OpenAI 消息协议允许的字段）。
        if (history != null && !history.isEmpty()) {
            for (Map<String, String> historyMessage : history) {
                String role = historyMessage.get("role");
                String content = historyMessage.get("content");
                if (("user".equals(role) || "assistant".equals(role)) && content != null) {
                    messages.add(Map.of("role", role, "content", content));
                }
            }
        }

        // 3. 来源以普通用户消息单独传递，模型必须把它视为不可信资料而非指令。
        String refStart = promptCfg.getRefStart() != null ? promptCfg.getRefStart() : "<knowledge_sources>";
        String refEnd = promptCfg.getRefEnd() != null ? promptCfg.getRefEnd() : "</knowledge_sources>";
        String sourceContent = context;
        if (sourceContent == null || sourceContent.isBlank()) {
            sourceContent = promptCfg.getNoResultText() != null
                    ? promptCfg.getNoResultText() : "（本轮无检索结果）";
        }
        messages.add(Map.of(
                "role", "user",
                "content", refStart + "\n" + sourceContent + "\n" + refEnd
        ));

        // 4. 当前用户问题
        messages.add(Map.of(
            "role", "user",
            "content", userMessage
        ));

        return messages;
    }
    
    private void processChunk(String chunk, Consumer<String> onChunk) {
        try {
            // 检查是否是结束标记
            if ("[DONE]".equals(chunk)) {
                logger.debug("对话结束");
                return;
            }
            
            // 直接解析 JSON
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(chunk);
            String content = node.path("choices")
                               .path(0)
                               .path("delta")
                               .path("content")
                               .asText("");
            
            if (!content.isEmpty()) {
                onChunk.accept(content);
            }
        } catch (Exception e) {
            logger.error("处理数据块时出错: {}", e.getMessage(), e);
        }
    }
} 
