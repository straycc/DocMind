package com.yizhaoqi.docmind.client;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Duration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.yizhaoqi.docmind.config.AiProperties;
import reactor.core.Disposable;

@Service
public class DeepSeekClient {

    private final WebClient webClient;
    private final String apiKey;
    private final String model;
    private final boolean thinkingEnabled;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final Logger logger = LoggerFactory.getLogger(DeepSeekClient.class);
    
    public DeepSeekClient(@Value("${deepseek.api.url}") String apiUrl,
                         @Value("${deepseek.api.key}") String apiKey,
                         @Value("${deepseek.api.model}") String model,
                         @Value("${deepseek.api.thinking-enabled:false}") boolean thinkingEnabled,
                         AiProperties aiProperties) {
        WebClient.Builder builder = WebClient.builder().baseUrl(apiUrl);
        
        // 只有当 API key 不为空时才添加 Authorization header
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        
        this.webClient = builder.build();
        this.apiKey = apiKey;
        this.model = model;
        this.thinkingEnabled = thinkingEnabled;
        this.aiProperties = aiProperties;
    }

    // 流式响应
    public Disposable streamResponse(String userMessage,
                                     String context,
                                     List<Map<String, String>> history,
                                     Consumer<String> onChunk,
                                     Consumer<Throwable> onError,
                                     Runnable onComplete) {
        
        Map<String, Object> request = buildRequest(userMessage, context, history);
        
        AtomicBoolean emittedContent = new AtomicBoolean(false);
        return webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .concatMapIterable(this::extractEventPayloads)
                .filter(payload -> !"[DONE]".equals(payload))
                .map(this::extractContent)
                .filter(content -> !content.isEmpty())
                .subscribe(
                    content -> {
                        emittedContent.set(true);
                        onChunk.accept(content);
                    },
                    onError,
                    () -> {
                        if (emittedContent.get()) {
                            onComplete.run();
                        } else {
                            onError.accept(new IllegalStateException("模型流已结束，但没有返回任何回答内容"));
                        }
                    }
                );
    }

    /**
     * 将依赖上下文的追问改写成可独立检索的问题。该调用不携带知识来源，
     * 只允许消解主题和指代，失败由上层降级为原问题。
     */
    public String rewriteQuery(String userMessage,
                               List<Map<String, String>> recentHistory,
                               int maxTokens,
                               Duration timeout) {
        StringBuilder dialogue = new StringBuilder();
        for (Map<String, String> message : recentHistory) {
            String role = "assistant".equals(message.get("role")) ? "助手" : "用户";
            dialogue.append(role).append("：").append(message.getOrDefault("content", "")).append('\n');
        }

        String instruction = """
                你是检索问题改写器。根据最近对话，将当前问题改写成一个语义完整、可独立检索的问题。
                只能补全对话中已明确出现的主题、对象和限定条件，不得回答问题，不得增加新的事实。
                只输出改写后的问题，不要解释、不要编号、不要引号。如果当前问题已经独立完整，原样输出。
                """;
        String input = "最近对话：\n" + dialogue + "\n当前问题：\n" + userMessage;
        Map<String, Object> request = new java.util.HashMap<>();
        request.put("model", model);
        request.put("messages", List.of(
                Map.of("role", "system", "content", instruction),
                Map.of("role", "user", "content", input)
        ));
        request.put("stream", false);
        request.put("thinking", Map.of("type", "disabled"));
        request.put("temperature", 0);
        request.put("max_tokens", maxTokens);

        JsonNode response = webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(timeout)
                .block();
        String rewritten = response == null ? ""
                : response.path("choices").path(0).path("message").path("content").asText("").trim();
        if (rewritten.isBlank()) {
            throw new IllegalStateException("问题改写接口没有返回内容");
        }
        return stripWrappingQuotes(rewritten);
    }

    private String stripWrappingQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '“' && last == '”')) {
                return value.substring(1, value.length() - 1).trim();
            }
        }
        return value;
    }

    // 构建请求
    Map<String, Object> buildRequest(String userMessage,
                                     String context,
                                     List<Map<String, String>> history) {
        logger.info("构建请求，模型：{}，思考模式：{}，用户消息：{}，上下文长度：{}，历史消息数：{}",
                   model,
                   thinkingEnabled ? "enabled" : "disabled",
                   userMessage, 
                   context != null ? context.length() : 0, 
                   history != null ? history.size() : 0);
        
        Map<String, Object> request = new java.util.HashMap<>();
        request.put("model", model);
        request.put("messages", buildMessages(userMessage, context, history));
        request.put("stream", true);
        // DeepSeek当前默认开启thinking。RAG问答只向前端展示最终content，默认关闭思考模式，
        // 避免max_tokens全部消耗在reasoning_content后没有最终回答。
        request.put("thinking", Map.of("type", thinkingEnabled ? "enabled" : "disabled"));
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
    
    /**
     * 兼容两种WebClient解码结果：SSE reader已经去掉data前缀的单个payload，
     * 以及普通字符串解码器返回的一个或多个完整SSE事件。
     */
    List<String> extractEventPayloads(String chunk) {
        if (chunk == null || chunk.isBlank()) {
            return Collections.emptyList();
        }
        String normalized = chunk.replace("\r\n", "\n").replace('\r', '\n').trim();
        String[] lines = normalized.split("\n", -1);
        boolean containsSseField = java.util.Arrays.stream(lines)
                .map(String::trim)
                .anyMatch(line -> line.startsWith("data:") || line.startsWith("event:")
                        || line.startsWith("id:") || line.startsWith("retry:") || line.startsWith(":"));
        if (!containsSseField) {
            return List.of(normalized);
        }

        List<String> payloads = new ArrayList<>();
        StringBuilder data = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                flushSseData(payloads, data);
            } else if (trimmed.startsWith("data:")) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(trimmed.substring("data:".length()).stripLeading());
            }
            // event/id/retry/注释行不属于模型响应正文，直接忽略。
        }
        flushSseData(payloads, data);
        return payloads;
    }

    private void flushSseData(List<String> payloads, StringBuilder data) {
        if (!data.isEmpty()) {
            payloads.add(data.toString().trim());
            data.setLength(0);
        }
    }

    /** 从OpenAI兼容流式响应中提取正文；解析失败必须进入onError，不能伪装成成功。 */
    String extractContent(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (node.has("error")) {
                String message = node.path("error").path("message").asText(node.path("error").toString());
                throw new IllegalStateException("模型服务返回错误: " + message);
            }

            JsonNode choice = node.path("choices").path(0);
            String content = choice.path("delta").path("content").asText("");
            if (content.isEmpty()) {
                // 兼容服务端忽略stream参数、直接返回普通Chat Completion的情况。
                content = choice.path("message").path("content").asText("");
            }
            return content;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("无法解析模型流式响应", exception);
        }
    }
} 
