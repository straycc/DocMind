package com.yizhaoqi.docmind.service;

import com.yizhaoqi.docmind.entity.DocumentElement;
import com.yizhaoqi.docmind.model.DocumentElementType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** 按元素边界生成用于检索的子块。 */
public final class DocumentChunker {
    private static final int TARGET_TOKENS = 480;
    private static final int HARD_LIMIT_TOKENS = 600;
    private static final int FORCED_SPLIT_OVERLAP = 64;
    private static final int MIN_PREFERRED_TOKENS = 160;

    private DocumentChunker() {
    }

    /**
     * @param embeddingPrefix 与正文一起发送给 Embedding 模型的固定前缀，例如“文件名 + 标题路径”。
     */
    public static List<ChunkDraft> chunk(String embeddingPrefix, List<DocumentElement> elements) {
        List<ChunkDraft> result = new ArrayList<>();
        List<DocumentElement> current = new ArrayList<>();
        int prefixTokens = CjkTokenEstimator.estimate(embeddingPrefix);
        int currentTokens = prefixTokens;
        for (DocumentElement element : elements) {
            String text = TextCleaner.clean(element.getText());
            if (text.isEmpty()) {
                continue;
            }
            DocumentElement normalized = new DocumentElement(element.getType(), text, element.getPageNumber(),
                    element.getSourceLocator(), element.getOrdinal());
            int elementTokens = CjkTokenEstimator.estimate(text);
            // 表格是独立语义单元，不能与相邻正文或另一张表混在同一个检索块中。
            if (normalized.getType() == DocumentElementType.TABLE) {
                flush(result, embeddingPrefix, current);
                currentTokens = prefixTokens;
                if (prefixTokens + elementTokens > HARD_LIMIT_TOKENS) {
                    for (DocumentElement split : splitLongElement(normalized, HARD_LIMIT_TOKENS - prefixTokens)) {
                        result.add(toDraft(embeddingPrefix, List.of(split)));
                    }
                } else {
                    result.add(toDraft(embeddingPrefix, List.of(normalized)));
                }
                continue;
            }
            if (prefixTokens + elementTokens > HARD_LIMIT_TOKENS && canSplit(normalized.getType())) {
                flush(result, embeddingPrefix, current);
                currentTokens = prefixTokens;
                for (DocumentElement split : splitLongElement(normalized, HARD_LIMIT_TOKENS - prefixTokens)) {
                    result.add(toDraft(embeddingPrefix, List.of(split)));
                }
                continue;
            }
            if (!current.isEmpty() && currentTokens + elementTokens > TARGET_TOKENS) {
                flush(result, embeddingPrefix, current);
                currentTokens = prefixTokens;
            }
            current.add(normalized);
            currentTokens += elementTokens;
        }
        flush(result, embeddingPrefix, current);
        return mergeShortAdjacentChunks(embeddingPrefix, result);
    }

    private static boolean canSplit(DocumentElementType type) {
        return type == DocumentElementType.NARRATIVE || type == DocumentElementType.TABLE;
    }

    private static void flush(List<ChunkDraft> result, String embeddingPrefix, List<DocumentElement> current) {
        if (!current.isEmpty()) {
            result.add(toDraft(embeddingPrefix, current));
            current.clear();
        }
    }

    private static ChunkDraft toDraft(String embeddingPrefix, List<DocumentElement> elements) {
        String text = elements.stream().map(DocumentElement::getText).reduce((a, b) -> a + "\n\n" + b).orElse("");
        Integer pageStart = elements.stream().map(DocumentElement::getPageNumber).filter(java.util.Objects::nonNull)
                .min(Integer::compareTo).orElse(null);
        Integer pageEnd = elements.stream().map(DocumentElement::getPageNumber).filter(java.util.Objects::nonNull)
                .max(Integer::compareTo).orElse(null);
        String locator = elements.stream().map(DocumentElement::getSourceLocator).filter(java.util.Objects::nonNull)
                .distinct().reduce((a, b) -> a + "," + b).orElse(null);
        String contextualText = embeddingPrefix == null || embeddingPrefix.isBlank() ? text : embeddingPrefix + "\n" + text;
        return new ChunkDraft(text, pageStart, pageEnd, locator,
                CjkTokenEstimator.estimate(contextualText));
    }

    private static List<DocumentElement> splitLongElement(DocumentElement element, int capacity) {
        if (element.getType() == DocumentElementType.TABLE) {
            return splitTable(element, capacity);
        }
        // 后续块会追加 overlap；先为 overlap 预留容量，才能保证最终块（含标题路径）不超硬上限。
        int payloadCapacity = Math.max(1, capacity - FORCED_SPLIT_OVERLAP);
        List<String> pieces = splitNarrative(element.getText(), payloadCapacity);
        List<DocumentElement> result = new ArrayList<>();
        String previous = null;
        for (String piece : pieces) {
            String content = previous == null ? piece : tailByTokens(previous, FORCED_SPLIT_OVERLAP) + "\n" + piece;
            result.add(new DocumentElement(element.getType(), content, element.getPageNumber(),
                    element.getSourceLocator(), element.getOrdinal()));
            previous = piece;
        }
        return result;
    }

    private static List<DocumentElement> splitTable(DocumentElement element, int capacity) {
        String[] rows = element.getText().split("\\n");
        if (rows.length <= 1) {
            return splitLongElement(new DocumentElement(DocumentElementType.NARRATIVE, element.getText(),
                    element.getPageNumber(), element.getSourceLocator(), element.getOrdinal()), capacity);
        }
        String header = rows[0];
        List<DocumentElement> result = new ArrayList<>();
        StringBuilder current = new StringBuilder(header);
        for (int i = 1; i < rows.length; i++) {
            if (CjkTokenEstimator.estimate(current + "\n" + rows[i]) > capacity && current.length() > header.length()) {
                result.add(new DocumentElement(DocumentElementType.TABLE, current.toString(), element.getPageNumber(),
                        element.getSourceLocator(), element.getOrdinal()));
                current = new StringBuilder(header);
            }
            current.append('\n').append(rows[i]);
        }
        if (!current.isEmpty()) {
            result.add(new DocumentElement(DocumentElementType.TABLE, current.toString(), element.getPageNumber(),
                    element.getSourceLocator(), element.getOrdinal()));
        }
        return result;
    }

    private static List<String> splitNarrative(String text, int capacity) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String[] sentences = text.split("(?<=[。！？；.!?;])|(?<=\\n)");
        for (String sentence : sentences) {
            appendWithinCapacity(result, current, sentence, capacity);
        }
        if (!current.isEmpty()) {
            result.add(current.toString().trim());
        }
        return result;
    }

    private static void appendWithinCapacity(List<String> result, StringBuilder current, String part, int capacity) {
        String value = part.trim();
        if (value.isEmpty()) {
            return;
        }
        if (CjkTokenEstimator.estimate(value) > capacity) {
            for (String word : value.split("(?<=\\s)|(?=\\s)")) {
                if (CjkTokenEstimator.estimate(word) > capacity) {
                    for (int i = 0; i < word.length(); i++) {
                        appendWithinCapacity(result, current, String.valueOf(word.charAt(i)), capacity);
                    }
                } else {
                    appendWithinCapacity(result, current, word, capacity);
                }
            }
            return;
        }
        if (!current.isEmpty() && CjkTokenEstimator.estimate(current + " " + value) > capacity) {
            result.add(current.toString().trim());
            current.setLength(0);
        }
        if (!current.isEmpty()) {
            current.append(' ');
        }
        current.append(value);
    }

    private static String tailByTokens(String text, int limit) {
        StringBuilder result = new StringBuilder();
        for (int i = text.length(); i > 0; ) {
            int start = text.offsetByCodePoints(i, -1);
            result.insert(0, text.substring(start, i));
            if (CjkTokenEstimator.estimate(result.toString()) >= limit) {
                break;
            }
            i = start;
        }
        return result.toString();
    }

    /**
     * Section 内的短表格标题、短说明等可和相邻内容合并；同一来源定位的块不合并，
     * 以免把强制拆分产生的 overlap 在同一 Chunk 内重复一次。
     */
    private static List<ChunkDraft> mergeShortAdjacentChunks(String embeddingPrefix, List<ChunkDraft> drafts) {
        List<ChunkDraft> merged = new ArrayList<>();
        for (ChunkDraft draft : drafts) {
            if (merged.isEmpty()) {
                merged.add(draft);
                continue;
            }
            ChunkDraft previous = merged.get(merged.size() - 1);
            ChunkDraft candidate = merge(embeddingPrefix, previous, draft);
            boolean hasShortChunk = previous.estimatedTokenCount() < MIN_PREFERRED_TOKENS
                    || draft.estimatedTokenCount() < MIN_PREFERRED_TOKENS;
            if (hasShortChunk && !Objects.equals(previous.sourceLocator(), draft.sourceLocator())
                    && candidate.estimatedTokenCount() <= HARD_LIMIT_TOKENS) {
                merged.set(merged.size() - 1, candidate);
            } else {
                merged.add(draft);
            }
        }
        return merged;
    }

    private static ChunkDraft merge(String embeddingPrefix, ChunkDraft left, ChunkDraft right) {
        String text = left.text() + "\n\n" + right.text();
        Integer pageStart = minPage(left.pageStart(), right.pageStart());
        Integer pageEnd = maxPage(left.pageEnd(), right.pageEnd());
        String locator = mergeLocator(left.sourceLocator(), right.sourceLocator());
        String contextualText = embeddingPrefix == null || embeddingPrefix.isBlank() ? text : embeddingPrefix + "\n" + text;
        return new ChunkDraft(text, pageStart, pageEnd, locator, CjkTokenEstimator.estimate(contextualText));
    }

    private static Integer minPage(Integer left, Integer right) {
        if (left == null) {
            return right;
        }
        return right == null ? left : Math.min(left, right);
    }

    private static Integer maxPage(Integer left, Integer right) {
        if (left == null) {
            return right;
        }
        return right == null ? left : Math.max(left, right);
    }

    private static String mergeLocator(String left, String right) {
        LinkedHashSet<String> locators = new LinkedHashSet<>();
        addLocators(locators, left);
        addLocators(locators, right);
        return locators.isEmpty() ? null : String.join(",", locators);
    }

    private static void addLocators(LinkedHashSet<String> locators, String locator) {
        if (locator == null || locator.isBlank()) {
            return;
        }
        for (String value : locator.split(",")) {
            if (!value.isBlank()) {
                locators.add(value);
            }
        }
    }

    public record ChunkDraft(String text, Integer pageStart, Integer pageEnd,
                             String sourceLocator, int estimatedTokenCount) {
    }
}
