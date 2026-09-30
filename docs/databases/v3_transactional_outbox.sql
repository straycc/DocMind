-- 文档处理任务的 Transactional Outbox。
-- file_upload 更新为 UPLOADED 时，在同一 MySQL 事务内写入一条 PENDING 事件。
CREATE TABLE outbox_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL,
    dedup_key VARCHAR(255) NOT NULL,
    aggregate_id BIGINT NOT NULL COMMENT 'file_upload.id',
    event_type VARCHAR(64) NOT NULL,
    topic VARCHAR(128) NOT NULL,
    message_key VARCHAR(128) NOT NULL,
    payload LONGTEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME NULL,
    locked_until DATETIME NULL,
    last_error VARCHAR(2000) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    published_at DATETIME NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_event_id (event_id),
    UNIQUE KEY uk_outbox_dedup_key (dedup_key),
    KEY idx_outbox_dispatch (status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='待发布领域事件';
