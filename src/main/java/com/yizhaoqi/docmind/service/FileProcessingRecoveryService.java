package com.yizhaoqi.docmind.service;

import com.yizhaoqi.docmind.model.FileProcessingTask;
import com.yizhaoqi.docmind.model.FileUpload;
import com.yizhaoqi.docmind.repository.FileUploadRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 在消息确认写入 DLT 后，将已耗尽重试的任务持久化为最终失败。 */
@Service
@Slf4j
public class FileProcessingRecoveryService {
    private final FileUploadRepository fileUploadRepository;
    private final DocumentProcessingStatusService statusService;

    public FileProcessingRecoveryService(FileUploadRepository fileUploadRepository,
                                         DocumentProcessingStatusService statusService) {
        this.fileUploadRepository = fileUploadRepository;
        this.statusService = statusService;
    }

    public void markFailedAfterDlt(Object payload, Throwable error) {
        if (!(payload instanceof FileProcessingTask task)) {
            log.error("DLT消息不是FileProcessingTask，无法更新文档状态: payloadType={}",
                    payload == null ? "null" : payload.getClass().getName());
            return;
        }
        Long fileUploadId = task.getFileUploadId();
        if (fileUploadId == null && task.getFileMd5() != null) {
            fileUploadId = fileUploadRepository.findByFileMd5(task.getFileMd5())
                    .map(FileUpload::getId)
                    .orElse(null);
        }
        if (fileUploadId == null) {
            log.error("DLT消息无法定位文件记录: eventId={}, fileMd5={}", task.getEventId(), task.getFileMd5());
            return;
        }
        statusService.markFailed(fileUploadId, error);
        log.warn("文件处理重试耗尽并进入DLT: fileUploadId={}, eventId={}", fileUploadId, task.getEventId());
    }
}
