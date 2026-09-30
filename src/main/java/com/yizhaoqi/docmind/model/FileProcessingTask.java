package com.yizhaoqi.docmind.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件处理任务类，用于Kafka消息传递
 */
@Data
@NoArgsConstructor
public class FileProcessingTask {
    private String eventId; // Outbox/Kafka 事件唯一标识，兼容旧消息时可为空
    private Long fileUploadId; // 文件事实记录 ID
    private String objectKey;  // MinIO 对象键，不是预签名 URL
    private String fileMd5; // 文件的 MD5 校验值
    private String filePath; // 文件存储路径
    private String fileName; // 文件名
    private String userId;   // 上传用户ID
    private String orgTag;   // 文件所属组织标签
    private boolean isPublic; // 文件是否公开

    public FileProcessingTask(Long fileUploadId, String objectKey, String fileMd5, String fileName,
                              String userId, String orgTag, boolean isPublic) {
        this.fileUploadId = fileUploadId;
        this.objectKey = objectKey;
        this.fileMd5 = fileMd5;
        this.filePath = objectKey;
        this.fileName = fileName;
        this.userId = userId;
        this.orgTag = orgTag;
        this.isPublic = isPublic;
    }
    
    /**
     * 向后兼容的构造函数
     */
    public FileProcessingTask(String fileMd5, String filePath, String fileName) {
        this.fileMd5 = fileMd5;
        this.filePath = filePath;
        this.fileName = fileName;
        this.userId = null;
        this.orgTag = "DEFAULT";
        this.isPublic = false;
    }

    /** 兼容旧生产者序列化的六字段消息。 */
    public FileProcessingTask(String fileMd5, String filePath, String fileName,
                              String userId, String orgTag, boolean isPublic) {
        this.fileMd5 = fileMd5;
        this.filePath = filePath;
        this.fileName = fileName;
        this.userId = userId;
        this.orgTag = orgTag;
        this.isPublic = isPublic;
    }
}
