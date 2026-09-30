-- Outbox发布者租约令牌，用于防止旧发布者覆盖其他实例已经接管的状态。
ALTER TABLE outbox_event
    ADD COLUMN lock_token VARCHAR(64) NULL AFTER locked_until,
    ADD INDEX idx_outbox_lease (status, locked_until);

-- 兼容切换前由Controller直接发送Kafka的历史任务：
-- 文档只要已经离开UPLOADED，就说明任务已经被消费者接收，不再从Outbox重复发送。
UPDATE outbox_event o
JOIN file_upload f ON f.id = o.aggregate_id
SET o.status = 'PUBLISHED',
    o.published_at = COALESCE(o.published_at, CURRENT_TIMESTAMP),
    o.lock_token = NULL,
    o.locked_until = NULL,
    o.last_error = NULL
WHERE o.status IN ('PENDING', 'PUBLISHING')
  AND f.processing_status IS NOT NULL
  AND f.processing_status <> 'UPLOADED';
