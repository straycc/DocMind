package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.FileProcessingTask;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** 将已领取的Outbox载荷发送至Kafka，并等待Broker确认事务提交。 */
@Service
public class KafkaOutboxMessageSender {
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public KafkaOutboxMessageSender(KafkaTemplate<String, Object> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void send(OutboxDispatchStateService.ClaimedOutboxEvent event, Duration timeout) {
        FileProcessingTask task;
        try {
            task = objectMapper.readValue(event.payload(), FileProcessingTask.class);
        } catch (Exception exception) {
            throw new IllegalArgumentException("反序列化Outbox事件失败: " + event.eventId(), exception);
        }

        kafkaTemplate.executeInTransaction(operations -> {
            try {
                operations.send(event.topic(), event.messageKey(), task)
                        .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                return true;
            } catch (Exception exception) {
                throw new IllegalStateException("Kafka未在超时时间内确认Outbox事件: " + event.eventId(), exception);
            }
        });
    }
}
