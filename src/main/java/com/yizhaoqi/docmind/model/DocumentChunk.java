package com.yizhaoqi.docmind.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

/**
 * 可检索的文档子块。向量是由此记录派生出的 ES 投影，不保存在该实体中。
 */
@Data
@Entity
@Table(name = "document_chunks",
        uniqueConstraints = @UniqueConstraint(name = "uk_document_chunk_version_ordinal",
                columnNames = {"file_upload_id", "chunker_version", "ordinal"}),
        indexes = {
                @Index(name = "idx_document_chunk_file", columnList = "file_upload_id"),
                @Index(name = "idx_document_chunk_section", columnList = "section_id,ordinal")
        })
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_upload_id", nullable = false)
    private Long fileUploadId;

    @Column(name = "section_id", nullable = false)
    private Long sectionId;

    @Column(nullable = false)
    private Integer ordinal;

    @Lob
    @Column(name = "text_content", nullable = false, columnDefinition = "LONGTEXT")
    private String textContent;

    @Column(name = "title_path", length = 2048)
    private String titlePath;

    @Column(name = "page_start")
    private Integer pageStart;

    @Column(name = "page_end")
    private Integer pageEnd;

    @Column(name = "source_locator", length = 2048)
    private String sourceLocator;

    @Column(name = "estimated_token_count", nullable = false)
    private Integer estimatedTokenCount;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "chunker_version", nullable = false, length = 32)
    private String chunkerVersion;
}
