CREATE TABLE users (
                       id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '用户唯一标识',
                       username VARCHAR(255) NOT NULL UNIQUE COMMENT '用户名，唯一',
                       password VARCHAR(255) NOT NULL COMMENT '加密后的密码',
                       role ENUM('USER', 'ADMIN') NOT NULL DEFAULT 'USER' COMMENT '用户角色',
                       org_tags VARCHAR(255) DEFAULT NULL COMMENT '用户所属组织标签，多个用逗号分隔',
                       primary_org VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '用户主组织标签',
                       created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                       updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                       INDEX idx_username (username) COMMENT '用户名索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';
CREATE TABLE organization_tags (
                                   tag_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin PRIMARY KEY COMMENT '标签唯一标识',
                                   name VARCHAR(100) NOT NULL COMMENT '标签名称',
                                   description TEXT COMMENT '描述',
                                   parent_tag VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '父标签ID',
                                   created_by BIGINT NOT NULL COMMENT '创建者ID',
                                   created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                   updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                   FOREIGN KEY (parent_tag) REFERENCES organization_tags(tag_id) ON DELETE SET NULL,
                                   FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组织标签表';


CREATE TABLE file_upload (
                             id           BIGINT           NOT NULL AUTO_INCREMENT COMMENT '主键',
                             file_md5     VARCHAR(32)      NOT NULL COMMENT '文件 MD5',
                             file_name    VARCHAR(255)     NOT NULL COMMENT '文件名称',
                             total_size   BIGINT           NOT NULL COMMENT '文件大小',
                             status       TINYINT          NOT NULL DEFAULT 0 COMMENT '上传状态',
                             object_key   VARCHAR(1024)    DEFAULT NULL COMMENT 'MinIO 合并对象键',
                             content_hash VARCHAR(64)      DEFAULT NULL COMMENT '文件 SHA-256',
                             parser_version VARCHAR(32)    DEFAULT NULL COMMENT '解析版本',
                             chunker_version VARCHAR(32)   DEFAULT NULL COMMENT '切片版本',
                             embedding_version VARCHAR(64) DEFAULT NULL COMMENT '向量模型版本',
                             processing_status VARCHAR(32) DEFAULT NULL COMMENT '处理生命周期状态',
                             processing_error TEXT          DEFAULT NULL COMMENT '处理失败原因',
                             user_id      VARCHAR(64)      NOT NULL COMMENT '用户 ID',
                             org_tag      VARCHAR(50)      DEFAULT NULL COMMENT '组织标签',
                             is_public    BOOLEAN          NOT NULL DEFAULT FALSE COMMENT '是否公开',                             created_at   TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                             merged_at    TIMESTAMP        NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '合并时间',
                             PRIMARY KEY (id),
                             UNIQUE KEY uk_md5_user (file_md5, user_id),
                             INDEX idx_user (user_id),
                             INDEX idx_org_tag (org_tag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件上传记录';
CREATE TABLE chunk_info (
                            id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '分块记录唯一标识',
                            file_md5 VARCHAR(32) NOT NULL COMMENT '关联的文件MD5值',
                            chunk_index INT NOT NULL COMMENT '分块序号',
                            chunk_md5 VARCHAR(32) NOT NULL COMMENT '分块的MD5值',
                            storage_path VARCHAR(255) NOT NULL COMMENT '分块在存储系统中的路径'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件分块信息表';

-- V1 新管线的事实表。实际向量仅作为 Elasticsearch 检索投影保存。
CREATE TABLE document_sections (
                                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                  file_upload_id BIGINT NOT NULL,
                                  ordinal INT NOT NULL,
                                  title_path VARCHAR(2048) DEFAULT NULL,
                                  page_start INT DEFAULT NULL,
                                  page_end INT DEFAULT NULL,
                                  INDEX idx_document_section_file (file_upload_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档章节父块';

CREATE TABLE document_chunks (
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

-- 业务状态与Kafka消息之间的可靠投递记录。
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
    lock_token VARCHAR(64) NULL,
    last_error VARCHAR(2000) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    published_at DATETIME NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_event_id (event_id),
    UNIQUE KEY uk_outbox_dedup_key (dedup_key),
    KEY idx_outbox_dispatch (status, next_retry_at),
    KEY idx_outbox_lease (status, locked_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='待发布领域事件';
