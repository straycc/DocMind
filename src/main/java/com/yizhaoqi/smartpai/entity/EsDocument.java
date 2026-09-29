package com.yizhaoqi.smartpai.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

/**
 * Elasticsearch存储的文档实体类
 * 包含文档内容和权限信息
 */
@Data
public class EsDocument {

    private String id;             // 文档唯一标识
    private Long fileUploadId;     // 文件事实记录 ID
    private String fileMd5;        // 文件指纹
    private String fileName;       // 原始文件名
    private Integer chunkId;       // 文本分块序号
    private Long sectionId;        // 父章节 ID
    private String textContent;    // 文本内容
    private String titlePath;      // 标题路径
    private Integer pageStart;
    private Integer pageEnd;
    private String sourceLocator;
    private Integer estimatedTokenCount;
    private float[] vector;        // 向量数据（768维）
    private String modelVersion;   // 向量生成模型版本
    private String userId;         // 上传用户ID
    private String orgTag;         // 组织标签
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private boolean publicDocument; // 是否公开

    /**
     * 默认构造函数，用于Jackson反序列化
     */
    public EsDocument() {
    }

    /**
     * 完整构造函数，包含权限字段
     */
    public EsDocument(String id, String fileMd5, int chunkId, String content, 
                     float[] vector, String modelVersion, 
                     String userId, String orgTag, boolean isPublic) {
        this.id = id;
        this.fileMd5 = fileMd5;
        this.chunkId = chunkId;
        this.textContent = content;
        this.vector = vector;
        this.modelVersion = modelVersion;
        this.userId = userId;
        this.orgTag = orgTag;
        this.publicDocument = isPublic;
    }

    public EsDocument(String id, Long fileUploadId, String fileMd5, String fileName, Integer chunkId, Long sectionId,
                      String textContent, String titlePath, Integer pageStart, Integer pageEnd,
                      String sourceLocator, Integer estimatedTokenCount, float[] vector, String modelVersion,
                      String userId, String orgTag, boolean isPublic) {
        this.id = id;
        this.fileUploadId = fileUploadId;
        this.fileMd5 = fileMd5;
        this.fileName = fileName;
        this.chunkId = chunkId;
        this.sectionId = sectionId;
        this.textContent = textContent;
        this.titlePath = titlePath;
        this.pageStart = pageStart;
        this.pageEnd = pageEnd;
        this.sourceLocator = sourceLocator;
        this.estimatedTokenCount = estimatedTokenCount;
        this.vector = vector;
        this.modelVersion = modelVersion;
        this.userId = userId;
        this.orgTag = orgTag;
        this.publicDocument = isPublic;
    }

    @JsonProperty("isPublic")
    public boolean isPublic() {
        return publicDocument;
    }

    @JsonProperty("isPublic")
    public void setPublic(boolean isPublic) {
        this.publicDocument = isPublic;
    }
    

}
