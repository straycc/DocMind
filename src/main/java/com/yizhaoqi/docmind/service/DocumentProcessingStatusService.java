package com.yizhaoqi.docmind.service;

import com.yizhaoqi.docmind.model.DocumentProcessingStatus;
import com.yizhaoqi.docmind.model.FileUpload;
import com.yizhaoqi.docmind.repository.FileUploadRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 使用独立短事务维护文档处理状态，避免状态更新参与已经失败的处理事务。 */
@Service
public class DocumentProcessingStatusService {
    private final FileUploadRepository fileUploadRepository;

    public DocumentProcessingStatusService(FileUploadRepository fileUploadRepository) {
        this.fileUploadRepository = fileUploadRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markParsing(Long fileUploadId) {
        update(fileUploadId, DocumentProcessingStatus.PARSING, null, false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markEmbedding(Long fileUploadId) {
        update(fileUploadId, DocumentProcessingStatus.EMBEDDING, null, false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markReady(Long fileUploadId, String embeddingVersion) {
        FileUpload upload = find(fileUploadId);
        upload.setEmbeddingVersion(embeddingVersion);
        upload.setProcessingStatus(DocumentProcessingStatus.READY);
        upload.setProcessingError(null);
        fileUploadRepository.save(upload);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRetrying(Long fileUploadId, Throwable error) {
        update(fileUploadId, DocumentProcessingStatus.RETRYING, describe(error), true);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long fileUploadId, Throwable error) {
        update(fileUploadId, DocumentProcessingStatus.FAILED, describe(error), true);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long fileUploadId, String error) {
        update(fileUploadId, DocumentProcessingStatus.FAILED, limit(error), true);
    }

    private void update(Long fileUploadId, DocumentProcessingStatus status, String error,
                        boolean preserveTerminalState) {
        FileUpload upload = find(fileUploadId);
        if (preserveTerminalState && (upload.getProcessingStatus() == DocumentProcessingStatus.READY
                || upload.getProcessingStatus() == DocumentProcessingStatus.NEEDS_OCR)) {
            return;
        }
        upload.setProcessingStatus(status);
        upload.setProcessingError(error);
        fileUploadRepository.save(upload);
    }

    private FileUpload find(Long fileUploadId) {
        return fileUploadRepository.findById(fileUploadId)
                .orElseThrow(() -> new IllegalArgumentException("文件上传记录不存在: " + fileUploadId));
    }

    private String describe(Throwable error) {
        if (error == null) {
            return "未知处理错误";
        }
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return limit(root.getClass().getSimpleName() + (message == null ? "" : ": " + message));
    }

    private String limit(String error) {
        String value = error == null || error.isBlank() ? "未知处理错误" : error;
        return value.substring(0, Math.min(4000, value.length()));
    }
}
