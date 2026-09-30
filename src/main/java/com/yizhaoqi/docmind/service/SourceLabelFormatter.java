package com.yizhaoqi.docmind.service;

/** 生成可核验引用标签；文件格式后缀只保留在下载/预览字段中，不出现在引用名内。 */
public final class SourceLabelFormatter {

    private SourceLabelFormatter() {
    }

    public static String format(String fileName, String titlePath, Integer pageStart, Integer pageEnd,
                                String textContent) {
        String name = fileName == null ? "未知文件" : DocumentEmbeddingTextBuilder.displayName(fileName);
        String label = "《" + name + "》";
        boolean hasTitle = titlePath != null && !titlePath.isBlank();
        if (hasTitle) {
            label += "｜" + titlePath;
        }
        if (pageStart != null) {
            return label + "｜第 " + pageStart + (pageEnd != null && !pageEnd.equals(pageStart)
                    ? "-" + pageEnd : "") + " 页";
        }
        return hasTitle ? label : label + "｜原文片段：“" + excerpt(textContent) + "”";
    }

    private static String excerpt(String text) {
        String normalized = TextCleaner.clean(text);
        return normalized.length() <= 40 ? normalized : normalized.substring(0, 40) + "…";
    }
}
