package com.yizhaoqi.smartpai.model;

/** 标题候选的可信度，用于避免页码、列表和表格数值污染文档层级。 */
public enum HeadingConfidence {
    HIGH,
    MEDIUM,
    LOW,
    REJECTED
}
