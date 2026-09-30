package com.yizhaoqi.docmind.service;

import com.yizhaoqi.docmind.model.DocumentProcessingStatus;
import com.yizhaoqi.docmind.model.FileUpload;
import com.yizhaoqi.docmind.repository.FileUploadRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/** 周期性发布Outbox事件；数据库租约保证多实例只由一个发布者处理同一事件。 */
@Slf4j
@Service
@ConditionalOnProperty(name = "outbox.dispatcher.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {
    private final OutboxDispatchStateService stateService;
    private final KafkaOutboxMessageSender messageSender;
    private final FileUploadRepository fileUploadRepository;
    private final int batchSize;
    private final int leaseSeconds;
    private final int sendTimeoutSeconds;
    private final int maxRetries;
    private final int baseBackoffSeconds;
    private final int maxBackoffSeconds;

    public OutboxPublisher(
            OutboxDispatchStateService stateService,
            KafkaOutboxMessageSender messageSender,
            FileUploadRepository fileUploadRepository,
            @Value("${outbox.dispatcher.batch-size:20}") int batchSize,
            @Value("${outbox.dispatcher.lease-seconds:60}") int leaseSeconds,
            @Value("${outbox.dispatcher.send-timeout-seconds:15}") int sendTimeoutSeconds,
            @Value("${outbox.dispatcher.max-retries:0}") int maxRetries,
            @Value("${outbox.dispatcher.base-backoff-seconds:5}") int baseBackoffSeconds,
            @Value("${outbox.dispatcher.max-backoff-seconds:300}") int maxBackoffSeconds) {
        this.stateService = stateService;
        this.messageSender = messageSender;
        this.fileUploadRepository = fileUploadRepository;
        this.batchSize = Math.max(1, batchSize);
        this.leaseSeconds = Math.max(10, leaseSeconds);
        this.sendTimeoutSeconds = Math.max(1, sendTimeoutSeconds);
        // 0表示对Kafka等临时基础设施故障无限重试；不可重试错误仍立即进入DEAD。
        this.maxRetries = Math.max(0, maxRetries);
        this.baseBackoffSeconds = Math.max(1, baseBackoffSeconds);
        this.maxBackoffSeconds = Math.max(this.baseBackoffSeconds, maxBackoffSeconds);
    }

    @Scheduled(fixedDelayString = "${outbox.dispatcher.fixed-delay-ms:5000}",
            initialDelayString = "${outbox.dispatcher.initial-delay-ms:5000}")
    public void publishPendingEvents() {
        try {
            int claimed = 0;
            for (int index = 0; index < batchSize; index++) {
                List<OutboxDispatchStateService.ClaimedOutboxEvent> events = stateService.claimBatch(
                        1, Duration.ofSeconds(leaseSeconds));
                if (events.isEmpty()) {
                    break;
                }
                claimed++;
                publishOne(events.get(0));
            }
            if (claimed > 0) {
                log.info("完成Outbox投递批次: claimed={}", claimed);
            }
        } catch (Exception exception) {
            // 防止一次轮询异常终止后续调度。
            log.error("Outbox投递批次执行失败", exception);
        }
    }

    private void publishOne(OutboxDispatchStateService.ClaimedOutboxEvent event) {
        try {
            FileUpload upload = fileUploadRepository.findById(event.aggregateId()).orElse(null);
            if (upload == null) {
                stateService.markDead(event, event.retryCount() + 1, "关联的文件上传记录不存在");
                log.warn("Outbox事件缺少关联文件，已标记DEAD: eventId={}, fileUploadId={}",
                        event.eventId(), event.aggregateId());
                return;
            }
            if (alreadyDeliveredByLegacyPath(upload)) {
                stateService.markPublished(event);
                log.info("历史Kafka直发任务已处理，Outbox状态收敛为PUBLISHED: eventId={}, fileUploadId={}, status={}",
                        event.eventId(), event.aggregateId(), upload.getProcessingStatus());
                return;
            }

            messageSender.send(event, Duration.ofSeconds(sendTimeoutSeconds));
            if (!stateService.markPublished(event)) {
                log.warn("Kafka已确认但Outbox租约失效，将由消费者幂等处理重复投递: eventId={}", event.eventId());
                return;
            }
            log.info("Outbox事件投递成功: eventId={}, fileUploadId={}, topic={}",
                    event.eventId(), event.aggregateId(), event.topic());
        } catch (Exception exception) {
            handleFailure(event, exception);
        }
    }

    private boolean alreadyDeliveredByLegacyPath(FileUpload upload) {
        DocumentProcessingStatus status = upload.getProcessingStatus();
        return status != null && status != DocumentProcessingStatus.UPLOADED;
    }

    private void handleFailure(OutboxDispatchStateService.ClaimedOutboxEvent event, Exception exception) {
        int retryCount = event.retryCount() + 1;
        String error = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        if (isNonRetryable(exception) || (maxRetries > 0 && retryCount >= maxRetries)) {
            stateService.markDead(event, retryCount, error);
            log.error("Outbox事件不可重试或超过最大重试次数，已标记DEAD: eventId={}, retries={}",
                    event.eventId(), retryCount, exception);
            return;
        }
        long delaySeconds = backoffSeconds(retryCount);
        stateService.reschedule(event, retryCount, LocalDateTime.now().plusSeconds(delaySeconds), error);
        log.warn("Outbox事件投递失败，等待重试: eventId={}, retryCount={}, delaySeconds={}, reason={}",
                event.eventId(), retryCount, delaySeconds, exception.getMessage());
    }

    private long backoffSeconds(int retryCount) {
        int exponent = Math.min(20, Math.max(0, retryCount - 1));
        long delay = baseBackoffSeconds * (1L << exponent);
        return Math.min(maxBackoffSeconds, delay);
    }

    private boolean isNonRetryable(Exception exception) {
        return exception instanceof IllegalArgumentException;
    }
}
