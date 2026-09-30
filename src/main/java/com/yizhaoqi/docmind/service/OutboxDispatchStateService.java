package com.yizhaoqi.docmind.service;

import com.yizhaoqi.docmind.model.OutboxEvent;
import com.yizhaoqi.docmind.model.OutboxStatus;
import com.yizhaoqi.docmind.repository.OutboxEventRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Outbox投递状态的短事务边界；Kafka网络调用不得放进这里。 */
@Service
public class OutboxDispatchStateService {
    private final OutboxEventRepository repository;

    public OutboxDispatchStateService(OutboxEventRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public List<ClaimedOutboxEvent> claimBatch(int batchSize, Duration leaseDuration) {
        LocalDateTime now = LocalDateTime.now();
        List<Long> candidateIds = repository.findDispatchCandidateIds(
                OutboxStatus.PENDING, OutboxStatus.PUBLISHING, now, PageRequest.of(0, batchSize));
        List<ClaimedOutboxEvent> claimed = new ArrayList<>(candidateIds.size());
        for (Long id : candidateIds) {
            String lockToken = UUID.randomUUID().toString();
            int updated = repository.claim(id, OutboxStatus.PENDING, OutboxStatus.PUBLISHING,
                    lockToken, now.plus(leaseDuration), now);
            if (updated != 1) {
                continue;
            }
            repository.findById(id).ifPresent(event -> claimed.add(toClaimed(event, lockToken)));
        }
        return claimed;
    }

    @Transactional
    public boolean markPublished(ClaimedOutboxEvent event) {
        LocalDateTime now = LocalDateTime.now();
        return repository.markPublished(event.id(), event.lockToken(), OutboxStatus.PUBLISHING,
                OutboxStatus.PUBLISHED, now) == 1;
    }

    @Transactional
    public boolean reschedule(ClaimedOutboxEvent event, int retryCount,
                              LocalDateTime nextRetryAt, String lastError) {
        LocalDateTime now = LocalDateTime.now();
        return repository.reschedule(event.id(), event.lockToken(), OutboxStatus.PUBLISHING,
                OutboxStatus.PENDING, retryCount, nextRetryAt, limit(lastError), now) == 1;
    }

    @Transactional
    public boolean markDead(ClaimedOutboxEvent event, int retryCount, String lastError) {
        LocalDateTime now = LocalDateTime.now();
        return repository.markDead(event.id(), event.lockToken(), OutboxStatus.PUBLISHING,
                OutboxStatus.DEAD, retryCount, limit(lastError), now) == 1;
    }

    private ClaimedOutboxEvent toClaimed(OutboxEvent event, String lockToken) {
        return new ClaimedOutboxEvent(event.getId(), event.getEventId(), event.getAggregateId(),
                event.getEventType(), event.getTopic(), event.getMessageKey(), event.getPayload(),
                event.getRetryCount(), lockToken);
    }

    private String limit(String error) {
        if (error == null || error.isBlank()) {
            return "未知投递错误";
        }
        return error.length() <= 2000 ? error : error.substring(0, 2000);
    }

    public record ClaimedOutboxEvent(Long id, String eventId, Long aggregateId, String eventType,
                                     String topic, String messageKey, String payload,
                                     int retryCount, String lockToken) {
    }
}
