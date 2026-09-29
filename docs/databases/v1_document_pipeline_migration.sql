-- V1 文件处理管线迁移（执行前请先备份数据库）。
-- 若使用 Hibernate ddl-auto=update，新增列和表会自动生成；生产环境建议显式执行本脚本。

ALTER TABLE file_upload
    ADD COLUMN object_key VARCHAR(1024) NULL COMMENT 'MinIO 合并对象键',
    ADD COLUMN content_hash VARCHAR(64) NULL COMMENT '文件 SHA-256',
    ADD COLUMN parser_version VARCHAR(32) NULL COMMENT '解析版本',
    ADD COLUMN chunker_version VARCHAR(32) NULL COMMENT '切片版本',
    ADD COLUMN embedding_version VARCHAR(64) NULL COMMENT '向量模型版本',
    ADD COLUMN processing_status VARCHAR(32) NULL COMMENT '处理生命周期状态',
    ADD COLUMN processing_error TEXT NULL COMMENT '处理失败原因';

CREATE TABLE IF NOT EXISTS document_sections (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    file_upload_id BIGINT NOT NULL,
    ordinal INT NOT NULL,
    title_path VARCHAR(2048) DEFAULT NULL,
    page_start INT DEFAULT NULL,
    page_end INT DEFAULT NULL,
    INDEX idx_document_section_file (file_upload_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档章节父块';

CREATE TABLE IF NOT EXISTS document_chunks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    file_upload_id BIGINT NOT NULL,
    section_id BIGINT NOT NULL,
    ordinal INT NOT NULL,
    text_content LONGTEXT NOT NULL,
    title_path VARCHAR(2048) DEFAULT NULL,
    page_start INT DEFAULT NULL,
    page_end INT DEFAULT NULL,
    source_locator VARCHAR(2048) DEFAULT NULL,
    estimated_token_count INT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    chunker_version VARCHAR(32) NOT NULL,
    UNIQUE KEY uk_document_chunk_version_ordinal (file_upload_id, chunker_version, ordinal),
    INDEX idx_document_chunk_file (file_upload_id),
    INDEX idx_document_chunk_section (section_id, ordinal)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档检索子块';

-- 旧 document_vectors 的清理由 v1_remove_legacy_vector_tables.sql 单独执行。
