package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.OutboxEvent;
import com.yizhaoqi.smartpai.model.OutboxStatus;
import com.yizhaoqi.smartpai.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxDispatchStateServiceTest {
    private OutboxEventRepository repository;
    private OutboxDispatchStateService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(OutboxEventRepository.class);
        service = new OutboxDispatchStateService(repository);
    }

    @Test
    void claimsEligibleEventWithLeaseToken() {
        OutboxEvent event = event();
        when(repository.findDispatchCandidateIds(any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(1L));
        when(repository.claim(Mockito.eq(1L), any(), any(), any(), any(), any())).thenReturn(1);
        when(repository.findById(1L)).thenReturn(Optional.of(event));

        List<OutboxDispatchStateService.ClaimedOutboxEvent> claimed =
                service.claimBatch(1, Duration.ofSeconds(60));

        assertEquals(1, claimed.size());
        assertEquals("event-1", claimed.get(0).eventId());
        assertFalse(claimed.get(0).lockToken().isBlank());

        ArgumentCaptor<LocalDateTime> nowCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> leaseCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).claim(Mockito.eq(1L), Mockito.eq(OutboxStatus.PENDING),
                Mockito.eq(OutboxStatus.PUBLISHING), any(), leaseCaptor.capture(), nowCaptor.capture());
        assertFalse(leaseCaptor.getValue().isBefore(nowCaptor.getValue().plusSeconds(59)));
    }

    @Test
    void marksPublishedOnlyForCurrentLeaseOwner() {
        OutboxDispatchStateService.ClaimedOutboxEvent claimed =
                new OutboxDispatchStateService.ClaimedOutboxEvent(
                        1L, "event-1", 7L, "DOCUMENT_PROCESS_REQUESTED", "file-processing",
                        "7", "{}", 0, "lock-1");
        when(repository.markPublished(Mockito.eq(1L), Mockito.eq("lock-1"),
                Mockito.eq(OutboxStatus.PUBLISHING), Mockito.eq(OutboxStatus.PUBLISHED), any()))
                .thenReturn(1);

        assertEquals(true, service.markPublished(claimed));
    }

    private OutboxEvent event() {
        OutboxEvent event = new OutboxEvent();
        event.setId(1L);
        event.setEventId("event-1");
        event.setAggregateId(7L);
        event.setEventType("DOCUMENT_PROCESS_REQUESTED");
        event.setTopic("file-processing");
        event.setMessageKey("7");
        event.setPayload("{}");
        event.setRetryCount(0);
        return event;
    }
}
