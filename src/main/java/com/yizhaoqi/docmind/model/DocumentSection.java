package com.yizhaoqi.docmind.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 文档中的 Section（Chunk 的轻量分组）。正文仅保存在 document_chunks。
 */
@Data
@Entity
@Table(name = "document_sections")
public class DocumentSection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_upload_id", nullable = false)
    private Long fileUploadId;

    @Column(nullable = false)
    private Integer ordinal;

    @Column(name = "title_path", length = 2048)
    private String titlePath;

    @Column(name = "page_start")
    private Integer pageStart;

    @Column(name = "page_end")
    private Integer pageEnd;

}
