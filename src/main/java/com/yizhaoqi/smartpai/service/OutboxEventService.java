package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.OutboxEvent;
import com.yizhaoqi.smartpai.model.OutboxStatus;
import com.yizhaoqi.smartpai.repository.OutboxEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/** 在业务事务内记录待发布的文档处理事件。 */
@Service
public class OutboxEventService {
    public static final String DOCUMENT_PROCESS_REQUESTED = "DOCUMENT_PROCESS_REQUESTED";
    public static final String PIPELINE_EVENT_VERSION = "v1";

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;
    private final String fileProcessingTopic;

    public OutboxEventService(OutboxEventRepository repository,
                              ObjectMapper objectMapper,
                              @Value("${spring.kafka.topic.file-processing}") String fileProcessingTopic) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.fileProcessingTopic = fileProcessingTopic;
    }

    /**
     * 调用方必须已经开启数据库事务，确保 file_upload 与 outbox_event 原子提交。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent recordDocumentProcessingRequested(FileUpload upload) {
        if (upload.getId() == null) {
            throw new IllegalArgumentException("创建Outbox事件前fileUploadId不能为空");
        }
        String dedupKey = DOCUMENT_PROCESS_REQUESTED + ":" + upload.getId() + ":" + PIPELINE_EVENT_VERSION;
        return repository.findByDedupKey(dedupKey).orElseGet(() -> repository.save(newEvent(upload, dedupKey)));
    }

    private OutboxEvent newEvent(FileUpload upload, String dedupKey) {
        String eventId = UUID.randomUUID().toString();
        FileProcessingTask task = new FileProcessingTask(
                upload.getId(), upload.getObjectKey(), upload.getFileMd5(), upload.getFileName(),
                upload.getUserId(), upload.getOrgTag(), upload.isPublic());
        task.setEventId(eventId);

        OutboxEvent event = new OutboxEvent();
        event.setEventId(eventId);
        event.setDedupKey(dedupKey);
        event.setAggregateId(upload.getId());
        event.setEventType(DOCUMENT_PROCESS_REQUESTED);
        event.setTopic(fileProcessingTopic);
        event.setMessageKey(upload.getId().toString());
        event.setPayload(writePayload(task));
        event.setStatus(OutboxStatus.PENDING);
        event.setRetryCount(0);
        event.setNextRetryAt(LocalDateTime.now());
        return event;
    }

    private String writePayload(FileProcessingTask task) {
        try {
            return objectMapper.writeValueAsString(task);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("序列化文档处理Outbox事件失败", exception);
        }
    }
}
