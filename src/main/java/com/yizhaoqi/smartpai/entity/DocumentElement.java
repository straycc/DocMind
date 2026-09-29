package com.yizhaoqi.smartpai.entity;

import com.yizhaoqi.smartpai.model.DocumentElementType;
import com.yizhaoqi.smartpai.model.HeadingConfidence;
import lombok.Data;

/** 解析阶段到切片阶段之间的结构化中间表示。 */
@Data
public class DocumentElement {
    private DocumentElementType type;
    private String text;
    private Integer pageNumber;
    private String sourceLocator;
    private Integer ordinal;
    /** 仅对 TITLE 有意义；不再从 sourceLocator 字符串反向解析。 */
    private Integer headingLevel;
    private HeadingConfidence headingConfidence;

    public DocumentElement(DocumentElementType type, String text, Integer pageNumber,
                           String sourceLocator, Integer ordinal) {
        this(type, text, pageNumber, sourceLocator, ordinal, null, null);
    }

    public DocumentElement(DocumentElementType type, String text, Integer pageNumber,
                           String sourceLocator, Integer ordinal, Integer headingLevel,
                           HeadingConfidence headingConfidence) {
        this.type = type;
        this.text = text;
        this.pageNumber = pageNumber;
        this.sourceLocator = sourceLocator;
        this.ordinal = ordinal;
        this.headingLevel = headingLevel;
        this.headingConfidence = headingConfidence;
    }
}
