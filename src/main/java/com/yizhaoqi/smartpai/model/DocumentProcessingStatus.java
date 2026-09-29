package com.yizhaoqi.smartpai.model;

/**
 * 文档处理生命周期。上传状态仍由 FileUpload.status 维护，
 * 此枚举仅描述解析、切片和向量化进度。
 */
public enum DocumentProcessingStatus {
    UPLOADED,
    PARSING,
    CHUNKED,
    EMBEDDING,
    READY,
    NEEDS_OCR,
    FAILED
}
