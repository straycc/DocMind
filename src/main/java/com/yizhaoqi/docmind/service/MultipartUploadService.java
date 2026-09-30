package com.yizhaoqi.docmind.service;

import com.yizhaoqi.docmind.dto.MultipartUploadDtos.CompleteResponse;
import com.yizhaoqi.docmind.dto.MultipartUploadDtos.InitRequest;
import com.yizhaoqi.docmind.dto.MultipartUploadDtos.InitResponse;
import com.yizhaoqi.docmind.dto.MultipartUploadDtos.PartView;
import com.yizhaoqi.docmind.dto.MultipartUploadDtos.PresignResponse;
import com.yizhaoqi.docmind.dto.MultipartUploadDtos.StatusResponse;
import com.yizhaoqi.docmind.model.DocumentProcessingStatus;
import com.yizhaoqi.docmind.model.FileUpload;
import com.yizhaoqi.docmind.repository.FileUploadRepository;
import com.yizhaoqi.docmind.storage.MultipartMinioClient;
import io.minio.messages.Part;
import io.minio.errors.ErrorResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class MultipartUploadService {
    private static final Logger logger = LoggerFactory.getLogger(MultipartUploadService.class);
    public static final String PROTOCOL = "S3_MULTIPART";
    public static final int STATUS_UPLOADING = 0;
    public static final int STATUS_COMPLETED = 1;
    public static final int STATUS_ABORTED = 2;

    private static final long MIB = 1024L * 1024L;
    private static final long MIN_PART_SIZE = 5L * MIB;
    private static final long DEFAULT_PART_SIZE = 16L * MIB;
    private static final long MAX_PART_SIZE = 5L * 1024L * MIB;
    private static final long MAX_OBJECT_SIZE = 5L * 1024L * 1024L * 1024L * 1024L;
    private static final int MAX_PARTS = 10_000;

    private final MultipartMinioClient minioClient;
    private final FileUploadRepository fileUploadRepository;
    private final UserService userService;
    private final FileTypeValidationService fileTypeValidationService;
    private final OutboxEventService outboxEventService;
    private final String bucket;
    private final int presignExpiryMinutes;

    public MultipartUploadService(
            MultipartMinioClient minioClient,
            FileUploadRepository fileUploadRepository,
            UserService userService,
            FileTypeValidationService fileTypeValidationService,
            OutboxEventService outboxEventService,
            @Value("${minio.bucketName:uploads}") String bucket,
            @Value("${upload.multipart.presign-expiry-minutes:15}") int presignExpiryMinutes) {
        this.minioClient = minioClient;
        this.fileUploadRepository = fileUploadRepository;
        this.userService = userService;
        this.fileTypeValidationService = fileTypeValidationService;
        this.outboxEventService = outboxEventService;
        this.bucket = bucket;
        this.presignExpiryMinutes = presignExpiryMinutes;
    }

    public InitResponse initiate(InitRequest request, String userId) {
        validateInit(request);
        String md5 = request.fileMd5().toLowerCase(Locale.ROOT);
        FileUpload existing = fileUploadRepository.findByFileMd5AndUserId(md5, userId).orElse(null);
        if (existing != null) {
            if (existing.getStatus() == STATUS_ABORTED && PROTOCOL.equals(existing.getUploadProtocol())) {
                return restartAborted(existing, request, userId);
            }
            return resumeExisting(existing, request);
        }

        long partSize = choosePartSize(request.totalSize(), request.partSize());
        int totalParts = partCount(request.totalSize(), partSize);
        String objectKey = "documents/" + UUID.randomUUID() + "/" + safeFileName(request.fileName());
        String uploadId = null;
        try {
            uploadId = minioClient.initiate(bucket, objectKey, request.contentType());

            FileUpload upload = new FileUpload();
            upload.setFileMd5(md5);
            upload.setFileName(request.fileName());
            upload.setTotalSize(request.totalSize());
            upload.setStatus(STATUS_UPLOADING);
            upload.setObjectKey(objectKey);
            upload.setUploadProtocol(PROTOCOL);
            upload.setMinioUploadId(uploadId);
            upload.setPartSize(partSize);
            upload.setTotalParts(totalParts);
            upload.setUserId(userId);
            upload.setOrgTag(resolveOrgTag(request.orgTag(), userId));
            upload.setPublic(request.isPublic());
            upload = fileUploadRepository.save(upload);
            logger.info("创建Multipart上传任务: fileUploadId={}, fileName={}, totalSize={}, partSize={}, totalParts={}",
                    upload.getId(), upload.getFileName(), upload.getTotalSize(), partSize, totalParts);
            return toInitResponse(upload);
        } catch (Exception e) {
            if (uploadId != null) {
                try {
                    minioClient.abort(bucket, objectKey, uploadId);
                } catch (Exception ignored) {
                    // Lifecycle cleanup is the final safety net for an orphaned upload.
                }
            }
            throw new IllegalStateException("创建MinIO Multipart上传会话失败", e);
        }
    }

    public PresignResponse presignPart(long fileUploadId, int partNumber, String userId) {
        FileUpload upload = ownedUpload(fileUploadId, userId);
        requireMultipartUploading(upload);
        if (partNumber < 1 || partNumber > upload.getTotalParts()) {
            throw new IllegalArgumentException("partNumber必须在1到" + upload.getTotalParts() + "之间");
        }
        try {
            String url = minioClient.presignPart(bucket, upload.getObjectKey(), upload.getMinioUploadId(),
                    partNumber, presignExpiryMinutes);
            return new PresignResponse(partNumber, url, presignExpiryMinutes * 60);
        } catch (Exception e) {
            throw new IllegalStateException("生成分片预签名URL失败", e);
        }
    }

    public StatusResponse status(long fileUploadId, String userId) {
        FileUpload upload = ownedUpload(fileUploadId, userId);
        if (upload.getStatus() == STATUS_COMPLETED) {
            long partSize = upload.getPartSize() == null ? upload.getTotalSize() : upload.getPartSize();
            int totalParts = upload.getTotalParts() == null ? 1 : upload.getTotalParts();
            return new StatusResponse(upload.getId(), "COMPLETED", partSize, totalParts,
                    List.of(), 100.0);
        }
        if (upload.getStatus() == STATUS_ABORTED) {
            return new StatusResponse(upload.getId(), "ABORTED", upload.getPartSize(), upload.getTotalParts(),
                    List.of(), 0.0);
        }
        requireMultipartUploading(upload);
        try {
            List<Part> parts = minioClient.parts(bucket, upload.getObjectKey(), upload.getMinioUploadId());
            List<PartView> views = parts.stream()
                    .map(part -> new PartView(part.partNumber(), part.etag(), part.partSize()))
                    .toList();
            long uploadedBytes = parts.stream().mapToLong(Part::partSize).sum();
            double progress = upload.getTotalSize() == 0 ? 0.0
                    : Math.min(100.0, uploadedBytes * 100.0 / upload.getTotalSize());
            int firstPart = parts.isEmpty() ? 0 : parts.get(0).partNumber();
            int lastPart = parts.isEmpty() ? 0 : parts.get(parts.size() - 1).partNumber();
            int nextPart = firstMissingPart(parts, upload.getTotalParts());
            logger.info("恢复Multipart上传状态: fileUploadId={}, uploadedParts={}/{}, firstPart={}, lastPart={}, nextMissingPart={}, uploadedBytes={}, progress={}%",
                    upload.getId(), parts.size(), upload.getTotalParts(), firstPart, lastPart, nextPart,
                    uploadedBytes, String.format(Locale.ROOT, "%.2f", progress));
            return new StatusResponse(upload.getId(), "UPLOADING", upload.getPartSize(), upload.getTotalParts(),
                    views, progress);
        } catch (Exception e) {
            throw new IllegalStateException("查询MinIO已上传分片失败", e);
        }
    }

    @Transactional
    public CompleteResponse complete(long fileUploadId, String userId) {
        FileUpload upload = fileUploadRepository.findOwnedByIdForUpdate(fileUploadId, userId)
                .orElseThrow(() -> new NoSuchElementException("上传任务不存在"));
        if (upload.getStatus() == STATUS_COMPLETED) {
            ensurePendingProcessingEvent(upload);
            return completedResponse(upload, false);
        }
        requireMultipartUploading(upload);
        try {
            CompleteResponse recovered = recoverCompletedObject(upload);
            if (recovered != null) {
                return recovered;
            }
            List<Part> parts = minioClient.parts(bucket, upload.getObjectKey(), upload.getMinioUploadId());
            validateCompletedParts(upload, parts);
            minioClient.finish(bucket, upload.getObjectKey(), upload.getMinioUploadId(), parts);

            long objectSize = minioClient.statSize(bucket, upload.getObjectKey());
            if (objectSize != upload.getTotalSize()) {
                throw new IllegalStateException("合并后对象大小不一致，期望" + upload.getTotalSize() + "，实际" + objectSize);
            }

            markCompleted(upload);
            logger.info("完成Multipart上传: fileUploadId={}, totalParts={}, totalSize={}, objectKey={}",
                    upload.getId(), upload.getTotalParts(), upload.getTotalSize(), upload.getObjectKey());
            return completedResponse(upload, true);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("完成Multipart上传失败", e);
        }
    }

    @Transactional
    public void deleteIncompleteUpload(long fileUploadId, String userId) {
        FileUpload upload = fileUploadRepository.findOwnedByIdForUpdate(fileUploadId, userId)
                .orElseThrow(() -> new NoSuchElementException("上传任务不存在"));
        if (upload.getStatus() == STATUS_COMPLETED) {
            throw new IllegalStateException("已完成文件请使用文档删除接口");
        }
        if (upload.getStatus() == STATUS_ABORTED) {
            fileUploadRepository.delete(upload);
            logger.info("删除历史已取消上传记录: fileUploadId={}, objectKey={}", upload.getId(), upload.getObjectKey());
            return;
        }
        requireMultipartUploading(upload);
        try {
            minioClient.abort(bucket, upload.getObjectKey(), upload.getMinioUploadId());
        } catch (Exception e) {
            if (!isNoSuchUpload(e)) {
                throw new IllegalStateException("终止MinIO Multipart上传失败", e);
            }
            logger.info("MinIO上传会话已不存在，继续删除数据库记录: fileUploadId={}", upload.getId());
        }
        fileUploadRepository.delete(upload);
        logger.info("彻底删除未完成上传任务: fileUploadId={}, objectKey={}", upload.getId(), upload.getObjectKey());
    }

    public FileUpload ownedUpload(long fileUploadId, String userId) {
        return fileUploadRepository.findByIdAndUserId(fileUploadId, userId)
                .orElseThrow(() -> new NoSuchElementException("上传任务不存在"));
    }

    private InitResponse resumeExisting(FileUpload existing, InitRequest request) {
        if (existing.getTotalSize() != request.totalSize()) {
            throw new IllegalArgumentException("相同MD5的上传任务文件大小不一致");
        }
        if (existing.getStatus() == STATUS_COMPLETED) {
            long partSize = existing.getPartSize() == null ? existing.getTotalSize() : existing.getPartSize();
            int totalParts = existing.getTotalParts() == null ? 1 : existing.getTotalParts();
            return new InitResponse(existing.getId(), null, existing.getObjectKey(), partSize, totalParts, "COMPLETED");
        }
        if (!PROTOCOL.equals(existing.getUploadProtocol()) || existing.getMinioUploadId() == null) {
            throw new IllegalStateException("该文件存在旧版未完成上传任务，请先删除或完成旧任务");
        }
        return toInitResponse(existing);
    }

    private InitResponse restartAborted(FileUpload upload, InitRequest request, String userId) {
        long partSize = choosePartSize(request.totalSize(), request.partSize());
        int totalParts = partCount(request.totalSize(), partSize);
        String objectKey = "documents/" + UUID.randomUUID() + "/" + safeFileName(request.fileName());
        String uploadId = null;
        try {
            uploadId = minioClient.initiate(bucket, objectKey, request.contentType());
            upload.setFileName(request.fileName());
            upload.setTotalSize(request.totalSize());
            upload.setStatus(STATUS_UPLOADING);
            upload.setObjectKey(objectKey);
            upload.setMinioUploadId(uploadId);
            upload.setPartSize(partSize);
            upload.setTotalParts(totalParts);
            upload.setOrgTag(resolveOrgTag(request.orgTag(), userId));
            upload.setPublic(request.isPublic());
            return toInitResponse(fileUploadRepository.save(upload));
        } catch (Exception e) {
            if (uploadId != null) {
                try {
                    minioClient.abort(bucket, objectKey, uploadId);
                } catch (Exception ignored) {
                    // Lifecycle cleanup is the final safety net.
                }
            }
            throw new IllegalStateException("重新创建MinIO Multipart上传会话失败", e);
        }
    }

    /** Recover the narrow crash window where MinIO completed but MySQL was not updated. */
    private CompleteResponse recoverCompletedObject(FileUpload upload) {
        try {
            if (minioClient.statSize(bucket, upload.getObjectKey()) == upload.getTotalSize()) {
                markCompleted(upload);
                return completedResponse(upload, true);
            }
        } catch (Exception ignored) {
            // Object does not exist yet (normal path), so continue with ListParts.
        }
        return null;
    }

    private void markCompleted(FileUpload upload) {
        upload.setStatus(STATUS_COMPLETED);
        upload.setMinioUploadId(null);
        upload.setProcessingStatus(DocumentProcessingStatus.UPLOADED);
        upload.setProcessingError(null);
        upload.setMergedAt(LocalDateTime.now());
        fileUploadRepository.save(upload);
        outboxEventService.recordDocumentProcessingRequested(upload);
    }

    /** 为历史故障窗口补齐任务；READY/NEEDS_OCR 等终态不会重复创建处理事件。 */
    private void ensurePendingProcessingEvent(FileUpload upload) {
        if (upload.getProcessingStatus() == DocumentProcessingStatus.UPLOADED) {
            outboxEventService.recordDocumentProcessingRequested(upload);
        }
    }

    private InitResponse toInitResponse(FileUpload upload) {
        return new InitResponse(upload.getId(), upload.getMinioUploadId(), upload.getObjectKey(),
                upload.getPartSize(), upload.getTotalParts(), "UPLOADING");
    }

    private CompleteResponse completedResponse(FileUpload upload, boolean newlyCompleted) {
        try {
            String url = minioClient.presignGet(bucket, upload.getObjectKey(), 60);
            return new CompleteResponse(upload.getId(), upload.getObjectKey(), url, "COMPLETED", newlyCompleted);
        } catch (Exception e) {
            throw new IllegalStateException("生成文件访问URL失败", e);
        }
    }

    private void validateCompletedParts(FileUpload upload, List<Part> parts) {
        if (parts.size() != upload.getTotalParts()) {
            throw new IllegalArgumentException("文件分片未上传完整，期望" + upload.getTotalParts() + "片，实际" + parts.size() + "片");
        }
        long totalBytes = 0;
        for (int index = 0; index < parts.size(); index++) {
            Part part = parts.get(index);
            int expectedNumber = index + 1;
            if (part.partNumber() != expectedNumber) {
                throw new IllegalArgumentException("缺少分片Part " + expectedNumber);
            }
            long expectedSize = expectedNumber == upload.getTotalParts()
                    ? upload.getTotalSize() - upload.getPartSize() * (upload.getTotalParts() - 1L)
                    : upload.getPartSize();
            if (part.partSize() != expectedSize) {
                throw new IllegalArgumentException("Part " + expectedNumber + "大小不正确");
            }
            totalBytes += part.partSize();
        }
        if (totalBytes != upload.getTotalSize()) {
            throw new IllegalArgumentException("已上传分片总大小与文件大小不一致");
        }
    }

    private int firstMissingPart(List<Part> parts, int totalParts) {
        int expected = 1;
        for (Part part : parts) {
            if (part.partNumber() != expected) {
                return expected;
            }
            expected++;
        }
        return expected <= totalParts ? expected : 0;
    }

    private boolean isNoSuchUpload(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ErrorResponseException error
                    && error.errorResponse() != null
                    && "NoSuchUpload".equals(error.errorResponse().code())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void validateInit(InitRequest request) {
        FileTypeValidationService.FileTypeValidationResult result =
                fileTypeValidationService.validateFileType(request.fileName());
        if (!result.isValid()) {
            throw new IllegalArgumentException(result.getMessage());
        }
        if (request.totalSize() > MAX_OBJECT_SIZE) {
            throw new IllegalArgumentException("文件超过S3 Multipart最大对象限制");
        }
    }

    private long choosePartSize(long totalSize, Long requestedPartSize) {
        long partSize = requestedPartSize == null ? DEFAULT_PART_SIZE : requestedPartSize;
        if (partSize < MIN_PART_SIZE || partSize > MAX_PART_SIZE) {
            throw new IllegalArgumentException("partSize必须在5MiB到5GiB之间");
        }
        long minimumForPartLimit = (totalSize + MAX_PARTS - 1) / MAX_PARTS;
        if (partSize < minimumForPartLimit) {
            partSize = ((minimumForPartLimit + MIB - 1) / MIB) * MIB;
        }
        return partSize;
    }

    private int partCount(long totalSize, long partSize) {
        int count = Math.toIntExact((totalSize + partSize - 1) / partSize);
        if (count < 1 || count > MAX_PARTS) {
            throw new IllegalArgumentException("Multipart分片数量必须在1到10000之间");
        }
        return count;
    }

    private String resolveOrgTag(String orgTag, String userId) {
        if (orgTag != null && !orgTag.isBlank()) {
            return orgTag;
        }
        return userService.getUserPrimaryOrg(userId);
    }

    private String safeFileName(String fileName) {
        String safe = fileName.replaceAll("[\\\\/\\p{Cntrl}]", "_").trim();
        return safe.isEmpty() ? "document" : safe;
    }

    private void requireMultipartUploading(FileUpload upload) {
        if (!PROTOCOL.equals(upload.getUploadProtocol()) || upload.getMinioUploadId() == null
                || upload.getPartSize() == null || upload.getTotalParts() == null) {
            throw new IllegalStateException("不是有效的S3 Multipart上传任务");
        }
        if (upload.getStatus() != STATUS_UPLOADING) {
            throw new IllegalStateException("上传任务当前状态不允许该操作");
        }
    }
}
