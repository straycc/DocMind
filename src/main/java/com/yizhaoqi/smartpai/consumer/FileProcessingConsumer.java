package com.yizhaoqi.smartpai.consumer;

import com.yizhaoqi.smartpai.config.KafkaConfig;
import com.yizhaoqi.smartpai.exception.NonRetryableFileProcessingException;
import com.yizhaoqi.smartpai.model.DocumentProcessingStatus;
import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.service.ParseService;
import com.yizhaoqi.smartpai.service.VectorizationService;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.GetObjectResponse;
import io.minio.errors.ErrorResponseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Set;

/** Kafka 至少一次消费的文档处理入口，只读取稳定的 MinIO objectKey。 */
@Service
@Slf4j
public class FileProcessingConsumer {
    private final ParseService parseService;
    private final VectorizationService vectorizationService;
    private final FileUploadRepository fileUploadRepository;
    private final MinioClient minioClient;
    private final String bucketName;

    public FileProcessingConsumer(ParseService parseService, VectorizationService vectorizationService,
                                 FileUploadRepository fileUploadRepository, MinioClient minioClient,
                                 @Value("${minio.bucketName:uploads}") String bucketName) {
        this.parseService = parseService;
        this.vectorizationService = vectorizationService;
        this.fileUploadRepository = fileUploadRepository;
        this.minioClient = minioClient;
        this.bucketName = bucketName;
    }

    @KafkaListener(topics = "#{kafkaConfig.getFileProcessingTopic()}", groupId = "#{kafkaConfig.getFileProcessingGroupId()}")
    public void processTask(FileProcessingTask task) {
        Long fileUploadId;
        try {
            fileUploadId = resolveFileUploadId(task);
        } catch (IllegalArgumentException exception) {
            throw new NonRetryableFileProcessingException("Kafka 任务无效: " + exception.getMessage(), exception);
        }
        FileUpload upload = fileUploadRepository.findById(fileUploadId)
                .orElseThrow(() -> new NonRetryableFileProcessingException("文件上传记录不存在: " + fileUploadId));
        String objectKey = task.getObjectKey() == null || task.getObjectKey().isBlank()
                ? upload.getObjectKey() : task.getObjectKey();
        if (objectKey == null || objectKey.isBlank()) {
            markFailed(upload, "Kafka 任务缺少 objectKey");
            throw new NonRetryableFileProcessingException("Kafka 任务缺少 objectKey");
        }
        try (GetObjectResponse object = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucketName).object(objectKey).build())) {
            DocumentProcessingStatus status = parseService.parseAndSave(fileUploadId, object);
            if (status == DocumentProcessingStatus.NEEDS_OCR) {
                log.info("扫描件待 OCR，跳过向量化: fileUploadId={}", fileUploadId);
                return;
            }
            vectorizationService.vectorize(fileUploadId);
            log.info("文件处理完成: fileUploadId={}, objectKey={}", fileUploadId, objectKey);
        } catch (Exception exception) {
            markFailed(upload, exception.getMessage());
            log.error("文件处理失败: fileUploadId={}, objectKey={}", fileUploadId, objectKey, exception);
            if (isNonRetryable(exception)) {
                throw new NonRetryableFileProcessingException("文件处理不可重试: " + fileUploadId, exception);
            }
            // 短暂异常交由 Kafka 指数退避重试；新表和 ES 的确定性记录保证重试不重复。
            throw new IllegalStateException("文件处理失败: " + fileUploadId, exception);
        }
    }

    /** 将明确的数据、对象或鉴权错误直接送入 DLT，避免无意义的重复调用。 */
    private boolean isNonRetryable(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof IllegalArgumentException) {
                return true;
            }
            if (current instanceof WebClientResponseException responseException
                    && responseException.getStatusCode().is4xxClientError()
                    && responseException.getStatusCode().value() != 429) {
                return true;
            }
            if (current instanceof ErrorResponseException minioException) {
                String code = minioException.errorResponse().code();
                if (Set.of("NoSuchKey", "NoSuchBucket", "AccessDenied", "InvalidAccessKeyId", "SignatureDoesNotMatch")
                        .contains(code)) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private Long resolveFileUploadId(FileProcessingTask task) {
        if (task.getFileUploadId() != null) {
            return task.getFileUploadId();
        }
        if (task.getFileMd5() == null || task.getFileMd5().isBlank()) {
            throw new IllegalArgumentException("Kafka 任务缺少 fileUploadId 和 fileMd5");
        }
        return fileUploadRepository.findByFileMd5(task.getFileMd5())
                .map(FileUpload::getId)
                .orElseThrow(() -> new IllegalArgumentException("文件上传记录不存在: " + task.getFileMd5()));
    }

    private void markFailed(FileUpload upload, String error) {
        upload.setProcessingStatus(DocumentProcessingStatus.FAILED);
        upload.setProcessingError(error == null ? "未知处理错误" : error.substring(0, Math.min(4000, error.length())));
        fileUploadRepository.save(upload);
    }
}
