package com.yizhaoqi.docmind.service;

/** 构造不落库的向量化输入，保证文件名、真实标题和正文的语义同时参与 Embedding。 */
public final class DocumentEmbeddingTextBuilder {

    private DocumentEmbeddingTextBuilder() {
    }

    public static String build(String fileName, String titlePath, String textContent) {
        String prefix = buildPrefix(fileName, titlePath);
        String text = TextCleaner.clean(textContent);
        if (prefix.isBlank()) {
            return text;
        }
        return text.isBlank() ? prefix : prefix + "\n" + text;
    }

    /** 构造与正文无关的 Embedding 前缀，切片阶段也必须使用它计算容量。 */
    public static String buildPrefix(String fileName, String titlePath) {
        String displayName = displayName(fileName);
        String title = TextCleaner.clean(titlePath);
        if (displayName.isBlank()) {
            return title;
        }
        return title.isBlank() ? displayName : displayName + "\n" + title;
    }

    /** 仅移除最后一个扩展名，文件原名仍保存在 file_upload 中用于下载和预览。 */
    public static String displayName(String fileName) {
        String value = TextCleaner.clean(fileName);
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }
}
