package com.yizhaoqi.docmind.service;

import com.yizhaoqi.docmind.entity.SearchResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 将检索结果转换为模型可读的来源块，并保留前端展示所需的可核验元数据。
 */
public final class RagAnswerContextBuilder {

    private static final int EXCERPT_MAX_LENGTH = 240;

    private RagAnswerContextBuilder() {
    }

    public static Context build(List<SearchResult> searchResults) {
        if (searchResults == null || searchResults.isEmpty()) {
            return new Context("", List.of());
        }

        List<Source> sources = new ArrayList<>();
        StringBuilder context = new StringBuilder();
        for (int index = 0; index < searchResults.size(); index++) {
            SearchResult result = searchResults.get(index);
            int sourceId = index + 1;
            String text = safe(result.getTextContent());
            String sourceLabel = firstNonBlank(result.getSourceLabel(), result.getFileName(), "未知来源");
            String pageLabel = pageLabel(result.getPageStart(), result.getPageEnd());

            sources.add(new Source(
                    sourceId,
                    result.getFileUploadId(),
                    result.getChunkId(),
                    result.getFileName(),
                    result.getTitlePath(),
                    result.getPageStart(),
                    result.getPageEnd(),
                    sourceLabel,
                    excerpt(text)
            ));

            context.append("[来源#").append(sourceId).append("]\n")
                    .append("来源：").append(sourceLabel).append('\n');
            appendIfPresent(context, "文件", result.getFileName());
            appendIfPresent(context, "标题路径", result.getTitlePath());
            appendIfPresent(context, "页码", pageLabel);
            context.append("正文：\n").append(text)
                    .append("\n[/来源#").append(sourceId).append("]\n\n");
        }
        return new Context(context.toString(), sources);
    }

    private static void appendIfPresent(StringBuilder builder, String label, String value) {
        if (value != null && !value.isBlank()) {
            builder.append(label).append('：').append(value).append('\n');
        }
    }

    private static String pageLabel(Integer pageStart, Integer pageEnd) {
        if (pageStart == null) {
            return null;
        }
        if (pageEnd == null || pageEnd.equals(pageStart)) {
            return "第 " + pageStart + " 页";
        }
        return "第 " + pageStart + "-" + pageEnd + " 页";
    }

    private static String excerpt(String text) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= EXCERPT_MAX_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, EXCERPT_MAX_LENGTH) + "…";
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "未知来源";
    }

    public record Context(String promptContext, List<Source> sources) {
        public Context {
            promptContext = promptContext == null ? "" : promptContext;
            sources = List.copyOf(sources == null ? List.of() : sources);
        }
    }

    /** 完成事件返回给前端的单条来源信息，不重复返回完整 Chunk 正文。 */
    public record Source(
            int sourceId,
            Long fileUploadId,
            Integer chunkOrdinal,
            String fileName,
            String titlePath,
            Integer pageStart,
            Integer pageEnd,
            String sourceLabel,
            String excerpt
    ) {
    }
}
