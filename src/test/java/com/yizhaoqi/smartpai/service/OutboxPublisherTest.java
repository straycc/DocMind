package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.DocumentProcessingStatus;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxPublisherTest {
    private OutboxDispatchStateService stateService;
    private KafkaOutboxMessageSender sender;
    private FileUploadRepository fileUploadRepository;
    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        stateService = Mockito.mock(OutboxDispatchStateService.class);
        sender = Mockito.mock(KafkaOutboxMessageSender.class);
        fileUploadRepository = Mockito.mock(FileUploadRepository.class);
        publisher = new OutboxPublisher(stateService, sender, fileUploadRepository,
                20, 60, 15, 3, 5, 300);
    }

    @Test
    void publishesUploadedDocumentAndMarksEventPublished() {
        OutboxDispatchStateService.ClaimedOutboxEvent event = event(0);
        when(stateService.claimBatch(1, Duration.ofSeconds(60)))
                .thenReturn(List.of(event), List.of());
        when(fileUploadRepository.findById(7L)).thenReturn(Optional.of(upload(DocumentProcessingStatus.UPLOADED)));
        when(stateService.markPublished(event)).thenReturn(true);

        publisher.publishPendingEvents();

        verify(sender).send(event, Duration.ofSeconds(15));
        verify(stateService).markPublished(event);
    }

    @Test
    void reconcilesLegacyDirectDeliveryWithoutSendingAgain() {
        OutboxDispatchStateService.ClaimedOutboxEvent event = event(0);
        when(stateService.claimBatch(1, Duration.ofSeconds(60)))
                .thenReturn(List.of(event), List.of());
        when(fileUploadRepository.findById(7L)).thenReturn(Optional.of(upload(DocumentProcessingStatus.READY)));

        publisher.publishPendingEvents();

        verify(sender, never()).send(any(), any());
        verify(stateService).markPublished(event);
    }

    @Test
    void reschedulesTransientKafkaFailure() {
        OutboxDispatchStateService.ClaimedOutboxEvent event = event(0);
        when(stateService.claimBatch(1, Duration.ofSeconds(60)))
                .thenReturn(List.of(event), List.of());
        when(fileUploadRepository.findById(7L)).thenReturn(Optional.of(upload(DocumentProcessingStatus.UPLOADED)));
        doThrow(new IllegalStateException("Kafka unavailable"))
                .when(sender).send(event, Duration.ofSeconds(15));

        publisher.publishPendingEvents();

        verify(stateService).reschedule(Mockito.eq(event), Mockito.eq(1),
                any(LocalDateTime.class), Mockito.contains("Kafka unavailable"));
        verify(stateService, never()).markDead(any(), anyInt(), any());
    }

    @Test
    void marksNonRetryablePayloadDeadImmediately() {
        OutboxDispatchStateService.ClaimedOutboxEvent event = event(0);
        when(stateService.claimBatch(1, Duration.ofSeconds(60)))
                .thenReturn(List.of(event), List.of());
        when(fileUploadRepository.findById(7L)).thenReturn(Optional.of(upload(DocumentProcessingStatus.UPLOADED)));
        doThrow(new IllegalArgumentException("invalid payload"))
                .when(sender).send(event, Duration.ofSeconds(15));

        publisher.publishPendingEvents();

        verify(stateService).markDead(event, 1, "IllegalArgumentException: invalid payload");
        verify(stateService, never()).reschedule(any(), anyInt(), any(), any());
    }

    private OutboxDispatchStateService.ClaimedOutboxEvent event(int retryCount) {
        return new OutboxDispatchStateService.ClaimedOutboxEvent(
                1L, "event-1", 7L, "DOCUMENT_PROCESS_REQUESTED", "file-processing",
                "7", "{}", retryCount, "lock-1");
    }

    private FileUpload upload(DocumentProcessingStatus status) {
        FileUpload upload = new FileUpload();
        upload.setId(7L);
        upload.setProcessingStatus(status);
        return upload;
    }
}
