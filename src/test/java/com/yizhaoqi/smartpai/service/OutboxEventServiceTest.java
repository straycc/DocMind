package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.OutboxEvent;
import com.yizhaoqi.smartpai.model.OutboxStatus;
import com.yizhaoqi.smartpai.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxEventServiceTest {
    private OutboxEventRepository repository;
    private ObjectMapper objectMapper;
    private OutboxEventService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(OutboxEventRepository.class);
        objectMapper = new ObjectMapper();
        service = new OutboxEventService(repository, objectMapper, "file-processing");
    }

    @Test
    void recordsPendingDocumentProcessingEventWithPayloadSnapshot() throws Exception {
        FileUpload upload = completedUpload();
        when(repository.findByDedupKey(any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        OutboxEvent result = service.recordDocumentProcessingRequested(upload);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        OutboxEvent event = captor.getValue();
        assertEquals(result, event);
        assertNotNull(event.getEventId());
        assertEquals("DOCUMENT_PROCESS_REQUESTED:7:v1", event.getDedupKey());
        assertEquals(7L, event.getAggregateId());
        assertEquals("file-processing", event.getTopic());
        assertEquals("7", event.getMessageKey());
        assertEquals(OutboxStatus.PENDING, event.getStatus());
        assertEquals(0, event.getRetryCount());

        JsonNode payload = objectMapper.readTree(event.getPayload());
        assertEquals(event.getEventId(), payload.get("eventId").asText());
        assertEquals(7L, payload.get("fileUploadId").asLong());
        assertEquals("documents/id/manual.pdf", payload.get("objectKey").asText());
        assertEquals("u1", payload.get("userId").asText());
    }

    @Test
    void returnsExistingEventForSameBusinessDedupKey() {
        FileUpload upload = completedUpload();
        OutboxEvent existing = new OutboxEvent();
        existing.setEventId("existing-event");
        when(repository.findByDedupKey("DOCUMENT_PROCESS_REQUESTED:7:v1"))
                .thenReturn(Optional.of(existing));

        OutboxEvent result = service.recordDocumentProcessingRequested(upload);

        assertEquals(existing, result);
        verify(repository, never()).save(any());
    }

    private FileUpload completedUpload() {
        FileUpload upload = new FileUpload();
        upload.setId(7L);
        upload.setFileMd5("0123456789abcdef0123456789abcdef");
        upload.setFileName("manual.pdf");
        upload.setObjectKey("documents/id/manual.pdf");
        upload.setUserId("u1");
        upload.setOrgTag("engineering");
        upload.setPublic(false);
        return upload;
    }
}
