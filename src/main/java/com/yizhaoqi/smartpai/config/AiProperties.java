package com.yizhaoqi.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 全局 AI 相关配置，包含 Prompt 模板和生成参数。
 */
@Component
@ConfigurationProperties(prefix = "ai")
@Data
public class AiProperties {

    private Prompt prompt = new Prompt();
    private Generation generation = new Generation();
    private History history = new History();
    private QueryRewrite queryRewrite = new QueryRewrite();

    @Data
    public static class Prompt {
        /** 规则文案 */
        private String rules;
        /** 引用开始分隔符 */
        private String refStart;
        /** 引用结束分隔符 */
        private String refEnd;
        /** 无检索结果时的占位文案 */
        private String noResultText;
    }

    @Data
    public static class Generation {
        /** 采样温度 */
        private Double temperature = 0.3;
        /** 最大输出 tokens */
        private Integer maxTokens = 2000;
        /** nucleus top-p */
        private Double topP = 0.9;
    }

    @Data
    public static class History {
        /** 发送给回答模型的历史上下文预算（近似 token 数）。 */
        private Integer maxTokens = 2500;
        /** Redis 中最多保留的消息数，作为存储上限而非模型上下文窗口。 */
        private Integer maxStoredMessages = 100;
    }

    @Data
    public static class QueryRewrite {
        private Boolean enabled = true;
        /** 用于消解指代的最近对话轮数。 */
        private Integer recentTurns = 3;
        private Integer maxTokens = 128;
        private Integer timeoutSeconds = 5;
    }
}
