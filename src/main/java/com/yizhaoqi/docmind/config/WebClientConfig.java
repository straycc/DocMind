package com.yizhaoqi.docmind.config;

import io.netty.channel.ChannelOption;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.ExchangeStrategies;

import java.time.Duration;

@Configuration
public class WebClientConfig {
    
    @Value("${embedding.api.url}")
    private String apiUrl;
    
    @Value("${embedding.api.key}")
    private String apiKey;

    @Value("${embedding.api.connect-timeout-ms:10000}")
    private int connectTimeoutMillis;

    @Value("${embedding.api.response-timeout-seconds:30}")
    private long responseTimeoutSeconds;

    @Value("${embedding.api.pool-max-idle-seconds:15}")
    private long poolMaxIdleSeconds;

    @Value("${embedding.api.pool-max-life-seconds:300}")
    private long poolMaxLifeSeconds;
    
    @Bean
    public WebClient embeddingWebClient() {
        ExchangeStrategies strategies = ExchangeStrategies.builder()
            .codecs(configurer -> configurer
                .defaultCodecs()
                .maxInMemorySize(16 * 1024 * 1024)) // 16MB
            .build();

        // DashScope 会主动关闭长时间空闲的 keep-alive 连接。主动淘汰旧连接，
        // 避免下一份文档复用半关闭连接后在首次读取时出现 Connection reset。
        ConnectionProvider connectionProvider = ConnectionProvider.builder("embedding-api-pool")
            .maxConnections(20)
            .pendingAcquireTimeout(Duration.ofSeconds(10))
            .maxIdleTime(Duration.ofSeconds(poolMaxIdleSeconds))
            .maxLifeTime(Duration.ofSeconds(poolMaxLifeSeconds))
            .evictInBackground(Duration.ofSeconds(poolMaxIdleSeconds))
            .lifo()
            .build();
        HttpClient httpClient = HttpClient.create(connectionProvider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
            .responseTimeout(Duration.ofSeconds(responseTimeoutSeconds));

        return WebClient.builder()
            .baseUrl(apiUrl)
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .exchangeStrategies(strategies)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();
    }
}
