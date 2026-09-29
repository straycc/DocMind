-- V1 Section 精简迁移（执行前请先备份数据库）。
-- 先通过前端删除需要重新解析的业务文件；本脚本不会删除任何文件或文档记录，
-- 但会永久删除 Section 的重复正文和 Chunk 的 embedding 输入列。
-- 该脚本面向已执行 v1_document_pipeline_migration.sql 的数据库，每条 ALTER 仅执行一次。

ALTER TABLE document_sections
    DROP COLUMN parent_section_id,
    DROP COLUMN title,
    DROP COLUMN content,
    DROP COLUMN parser_version;

ALTER TABLE document_chunks
    MODIFY COLUMN text_content LONGTEXT NOT NULL,
    DROP COLUMN embedding_text;
