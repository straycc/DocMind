-- MinIO/S3 原生 Multipart Upload 会话字段。
-- 旧分片接口可以继续使用；完成前端迁移和压测后再删除 chunk_info 表。
ALTER TABLE file_upload
    ADD COLUMN upload_protocol VARCHAR(32) NULL COMMENT 'LEGACY_CHUNK或S3_MULTIPART',
    ADD COLUMN minio_upload_id VARCHAR(255) NULL COMMENT 'MinIO multipart uploadId',
    ADD COLUMN part_size BIGINT NULL COMMENT 'Part大小（字节）',
    ADD COLUMN total_parts INT NULL COMMENT '总Part数';

-- 执行前先确认不存在同一用户、同一MD5的重复记录：
-- SELECT user_id, file_md5, COUNT(*) FROM file_upload GROUP BY user_id, file_md5 HAVING COUNT(*) > 1;
CREATE UNIQUE INDEX uk_file_upload_owner_md5
    ON file_upload (user_id, file_md5);

CREATE INDEX idx_file_upload_owner_status
    ON file_upload (user_id, status, upload_protocol);
