package com.yizhaoqi.docmind.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 文件上传实体类
 * 用于表示文件上传的相关信息
 */
@Data
@Entity
@Table(name = "file_upload", uniqueConstraints =
        @UniqueConstraint(name = "uk_file_upload_owner_md5", columnNames = {"user_id", "file_md5"}))
public class FileUpload {
    /**
     * 文件的唯一标识符
     * 使用文件的MD5值来唯一确定一个文件
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id; // 自增主键

    @Column(name = "file_md5", length = 32, nullable = false)
    private String fileMd5;

    /**
     * 文件的原始名称
     * 用于记录上传时文件的名称
     */
    private String fileName;

    /**
     * 文件的总大小
     * 以字节为单位记录文件的大小
     */
    private long totalSize;

    /**
     * 文件上传的状态
     * 0表示文件正在上传中，1表示文件上传已完成
     */
    private int status; // 0-上传中 1-已完成

    /** MinIO 中的原始合并文件对象键，避免消费端依赖会过期的预签名 URL。 */
    @Column(name = "object_key", length = 1024)
    private String objectKey;

    /** 上传协议：LEGACY_CHUNK 或 S3_MULTIPART。 */
    @Column(name = "upload_protocol", length = 32)
    private String uploadProtocol;

    /** MinIO 原生 Multipart Upload 会话 ID，仅在上传期间有效。 */
    @Column(name = "minio_upload_id", length = 255)
    private String minioUploadId;

    /** Multipart 分片大小，单位字节。 */
    @Column(name = "part_size")
    private Long partSize;

    /** Multipart 总分片数。 */
    @Column(name = "total_parts")
    private Integer totalParts;

    /** 合并后的文件内容哈希，用于重建和完整性追踪。 */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "parser_version", length = 32)
    private String parserVersion;

    @Column(name = "chunker_version", length = 32)
    private String chunkerVersion;

    @Column(name = "embedding_version", length = 64)
    private String embeddingVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", length = 32)
    private DocumentProcessingStatus processingStatus;

    @Lob
    @Column(name = "processing_error")
    private String processingError;

    /**
     * 上传文件的用户的标识符
     * 用于记录哪个用户上传了文件
     */
    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;
    
    /**
     * 文件所属组织标签
     * 用于标识文件归属的组织，支持基于组织标签的权限控制
     */
    @Column(name = "org_tag")
    private String orgTag;

    /**
     * 文件是否公开
     * true表示所有用户可访问，false表示仅组织内用户可访问
     */
    @Column(name = "is_public", nullable = false)
    private boolean isPublic = false;

    /**
     * 文件上传的创建时间
     * 自动记录文件上传开始的时间
     */
    @CreationTimestamp
    private LocalDateTime createdAt;

    /**
     * 文件合并完成的时间
     * 当文件上传状态为已完成时，自动记录完成的时间
     */
    @UpdateTimestamp
    private LocalDateTime mergedAt;
}

