package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.DocumentProcessingStatus;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 仅供运维在暂停 Kafka 消费并完成数据库备份后启用的一次性全量重建入口。 */
@Component
@Order(100)
@Slf4j
@ConditionalOnProperty(name = "document.reindex-all", havingValue = "true")
public class DocumentReindexRunner implements CommandLineRunner {
    private final FileUploadRepository fileUploadRepository;
    private final ParseService parseService;
    private final VectorizationService vectorizationService;
    private final MinioClient minioClient;
    private final String bucketName;

    public DocumentReindexRunner(FileUploadRepository fileUploadRepository, ParseService parseService,
                                 VectorizationService vectorizationService, MinioClient minioClient,
                                 @Value("${minio.bucketName:uploads}") String bucketName) {
        this.fileUploadRepository = fileUploadRepository;
        this.parseService = parseService;
        this.vectorizationService = vectorizationService;
        this.minioClient = minioClient;
        this.bucketName = bucketName;
    }

    @Override
    public void run(String... args) {
        log.warn("开始一次性文档重建；请确认已备份数据库且 Kafka 新文件消费已暂停");
        for (FileUpload upload : fileUploadRepository.findByStatus(1)) {
            String objectKey = upload.getObjectKey() == null ? "merged/" + upload.getFileName() : upload.getObjectKey();
            try (GetObjectResponse object = minioClient.getObject(GetObjectArgs.builder().bucket(bucketName).object(objectKey).build())) {
                DocumentProcessingStatus status = parseService.parseAndSave(upload.getId(), object);
                if (status != DocumentProcessingStatus.NEEDS_OCR) {
                    vectorizationService.vectorize(upload.getId());
                }
            } catch (Exception exception) {
                log.error("重建失败: fileUploadId={}, fileName={}", upload.getId(), upload.getFileName(), exception);
            }
        }
        log.warn("一次性文档重建完成；抽查检索引用后再切换索引并关闭 document.reindex-all");
    }
}
