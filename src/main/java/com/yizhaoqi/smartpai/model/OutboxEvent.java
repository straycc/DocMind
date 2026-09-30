package com.yizhaoqi.smartpai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 与业务状态在同一 MySQL 事务中写入的待发布事件。
 * Kafka 投递器基于该表实现可恢复的最终投递。
 */
@Data
@Entity
@Table(name = "outbox_event",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_outbox_event_id", columnNames = "event_id"),
                @UniqueConstraint(name = "uk_outbox_dedup_key", columnNames = "dedup_key")
        },
        indexes = {
                @Index(name = "idx_outbox_dispatch", columnList = "status,next_retry_at"),
                @Index(name = "idx_outbox_lease", columnList = "status,locked_until")
        })
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(name = "dedup_key", nullable = false, length = 255)
    private String dedupKey;

    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(nullable = false, length = 128)
    private String topic;

    @Column(name = "message_key", nullable = false, length = 128)
    private String messageKey;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    @Column(name = "lock_token", length = 64)
    private String lockToken;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;
}
