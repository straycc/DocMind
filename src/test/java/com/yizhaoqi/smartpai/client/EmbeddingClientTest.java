package com.yizhaoqi.smartpai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingClientTest {

    private final EmbeddingClient client = new EmbeddingClient(WebClient.create(), new ObjectMapper());

    @Test
    void retriesConnectionResetAndOtherRequestFailures() {
        WebClientRequestException exception = new WebClientRequestException(
                new java.net.SocketException("Connection reset"), HttpMethod.POST,
                URI.create("https://dashscope.aliyuncs.com/embeddings"), HttpHeaders.EMPTY);

        assertTrue(client.isTransientFailure(exception));
    }

    @Test
    void retriesRateLimitAndServerErrorsButNotOrdinaryClientErrors() {
        assertTrue(client.isTransientFailure(responseException(429)));
        assertTrue(client.isTransientFailure(responseException(503)));
        assertFalse(client.isTransientFailure(responseException(400)));
        assertFalse(client.isTransientFailure(new IllegalArgumentException("bad payload")));
    }

    private WebClientResponseException responseException(int status) {
        return WebClientResponseException.create(status, "test", HttpHeaders.EMPTY, new byte[0], null);
    }
}
