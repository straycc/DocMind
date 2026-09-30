package com.yizhaoqi.smartpai.client;

import com.yizhaoqi.smartpai.config.AiProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeepSeekClientTest {
    private final DeepSeekClient client = new DeepSeekClient(
            "http://localhost", "", "test-model", false, new AiProperties());

    @Test
    void disablesThinkingForRagAnswersByDefault() {
        java.util.Map<String, Object> request = client.buildRequest("问题", "资料", List.of());

        assertEquals(java.util.Map.of("type", "disabled"), request.get("thinking"));
    }

    @Test
    void extractsMultipleSseEventsFromOneNetworkChunk() {
        String chunk = "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"员工请假\"}}]}\n\n"
                + "data: [DONE]\n\n";

        List<String> payloads = client.extractEventPayloads(chunk);

        assertEquals(3, payloads.size());
        assertEquals("", client.extractContent(payloads.get(0)));
        assertEquals("员工请假", client.extractContent(payloads.get(1)));
        assertEquals("[DONE]", payloads.get(2));
    }

    @Test
    void acceptsPayloadAlreadyDecodedBySseReader() {
        String payload = "{\"choices\":[{\"delta\":{\"content\":\"审批流程\"}}]}";

        assertEquals(List.of(payload), client.extractEventPayloads(payload));
        assertEquals("审批流程", client.extractContent(payload));
    }

    @Test
    void supportsNonStreamingCompatibleResponse() {
        String payload = "{\"choices\":[{\"message\":{\"content\":\"普通响应\"}}]}";

        assertEquals("普通响应", client.extractContent(payload));
    }

    @Test
    void propagatesProviderErrorInsteadOfSilentlyCompleting() {
        String payload = "{\"error\":{\"message\":\"invalid model\"}}";

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client.extractContent(payload));
        assertEquals("模型服务返回错误: invalid model", error.getMessage());
    }

    @Test
    void propagatesMalformedPayloadInsteadOfDiscardingIt() {
        assertThrows(IllegalStateException.class, () -> client.extractContent("not-json"));
    }
}
