package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.entity.DocumentElement;
import com.yizhaoqi.smartpai.model.DocumentElementType;
import com.yizhaoqi.smartpai.model.HeadingConfidence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将解析元素组织为扁平 Section。Section 只负责语义或物理分组，正文始终由 Chunk 保存。
 */
public final class DocumentSectionPlanner {
    static final int FALLBACK_TARGET_TOKENS = 1_800;
    static final int FALLBACK_HARD_LIMIT_TOKENS = 2_400;

    private DocumentSectionPlanner() {
    }

    /**
     * 优先使用真实标题；没有标题时按页边界或自然元素生成内部 Section。
     */
    public static List<SectionDraft> plan(List<DocumentElement> sourceElements) {
        List<DocumentElement> elements = sourceElements.stream()
                .filter(element -> element != null && TextCleaner.clean(element.getText()).length() > 0)
                .sorted(Comparator.comparing(DocumentElement::getOrdinal, Comparator.nullsLast(Integer::compareTo)))
                .map(DocumentSectionPlanner::downgradeNonSectionHeading)
                .toList();
        if (elements.stream().anyMatch(element -> element.getType() == DocumentElementType.TITLE)) {
            return planByHeadings(elements);
        }
        return planFallback(elements);
    }

    private static List<SectionDraft> planByHeadings(List<DocumentElement> elements) {
        List<SectionDraft> result = new ArrayList<>();
        Map<Integer, String> hierarchy = new LinkedHashMap<>();
        List<DocumentElement> current = new ArrayList<>();
        String currentTitlePath = null;

        for (DocumentElement element : elements) {
            if (element.getType() != DocumentElementType.TITLE) {
                current.add(element);
                continue;
            }
            addIfNotEmpty(result, currentTitlePath, current);
            current = new ArrayList<>();
            int level = headingLevel(element);
            hierarchy.entrySet().removeIf(entry -> entry.getKey() >= level);
            hierarchy.put(level, TextCleaner.clean(element.getText()));
            currentTitlePath = hierarchy.values().stream()
                    .filter(value -> !value.isBlank())
                    .reduce((left, right) -> left + "｜" + right)
                    .orElse(null);
        }
        addIfNotEmpty(result, currentTitlePath, current);
        return result;
    }

    private static List<SectionDraft> planFallback(List<DocumentElement> elements) {
        if (elements.stream().anyMatch(element -> element.getPageNumber() != null)) {
            return mergePageUnits(elements);
        }
        return mergeNaturalElements(elements);
    }

    /** 页边界优先：只在页之间合并，允许 Section 在目标值和硬上限之间。 */
    private static List<SectionDraft> mergePageUnits(List<DocumentElement> elements) {
        List<List<DocumentElement>> units = new ArrayList<>();
        List<DocumentElement> currentPage = new ArrayList<>();
        Integer page = null;
        for (DocumentElement element : elements) {
            if (!currentPage.isEmpty() && !java.util.Objects.equals(page, element.getPageNumber())) {
                units.add(currentPage);
                currentPage = new ArrayList<>();
            }
            page = element.getPageNumber();
            currentPage.add(element);
        }
        if (!currentPage.isEmpty()) {
            units.add(currentPage);
        }

        List<SectionDraft> result = new ArrayList<>();
        List<DocumentElement> current = new ArrayList<>();
        int tokens = 0;
        for (List<DocumentElement> unit : units) {
            int unitTokens = estimatedTokens(unit);
            if (!current.isEmpty() && (tokens >= FALLBACK_TARGET_TOKENS
                    || tokens + unitTokens > FALLBACK_HARD_LIMIT_TOKENS)) {
                addIfNotEmpty(result, null, current);
                current = new ArrayList<>();
                tokens = 0;
            }
            current.addAll(unit);
            tokens += unitTokens;
        }
        addIfNotEmpty(result, null, current);
        return result;
    }

    /** 没有页码时按段落、列表、表格等完整元素合并，避免把元素截断在 Section 边界。 */
    private static List<SectionDraft> mergeNaturalElements(List<DocumentElement> elements) {
        List<SectionDraft> result = new ArrayList<>();
        List<DocumentElement> current = new ArrayList<>();
        int tokens = 0;
        for (DocumentElement element : elements) {
            int elementTokens = CjkTokenEstimator.estimate(element.getText());
            if (!current.isEmpty() && tokens + elementTokens > FALLBACK_TARGET_TOKENS) {
                addIfNotEmpty(result, null, current);
                current = new ArrayList<>();
                tokens = 0;
            }
            current.add(element);
            tokens += elementTokens;
        }
        addIfNotEmpty(result, null, current);
        return result;
    }

    private static int estimatedTokens(List<DocumentElement> elements) {
        return elements.stream().map(DocumentElement::getText).mapToInt(CjkTokenEstimator::estimate).sum();
    }

    private static void addIfNotEmpty(List<SectionDraft> result, String titlePath, List<DocumentElement> elements) {
        if (elements.isEmpty()) {
            return;
        }
        Integer pageStart = elements.stream().map(DocumentElement::getPageNumber).filter(java.util.Objects::nonNull)
                .min(Integer::compareTo).orElse(null);
        Integer pageEnd = elements.stream().map(DocumentElement::getPageNumber).filter(java.util.Objects::nonNull)
                .max(Integer::compareTo).orElse(null);
        result.add(new SectionDraft(titlePath, List.copyOf(elements), pageStart, pageEnd));
    }

    /** 只让可信的一、二级标题划分 Section；其余标题文字保留在 Chunk 正文中。 */
    private static DocumentElement downgradeNonSectionHeading(DocumentElement element) {
        if (element.getType() != DocumentElementType.TITLE || isSectionHeading(element)) {
            return element;
        }
        return new DocumentElement(DocumentElementType.NARRATIVE, element.getText(), element.getPageNumber(),
                element.getSourceLocator(), element.getOrdinal(), element.getHeadingLevel(),
                element.getHeadingConfidence());
    }

    private static boolean isSectionHeading(DocumentElement element) {
        HeadingConfidence confidence = element.getHeadingConfidence();
        if (confidence == HeadingConfidence.LOW || confidence == HeadingConfidence.REJECTED) {
            return false;
        }
        return headingLevel(element) <= 2;
    }

    static int headingLevel(DocumentElement element) {
        if (element.getHeadingLevel() != null) {
            return element.getHeadingLevel();
        }
        return headingLevel(element.getSourceLocator());
    }

    static int headingLevel(String locator) {
        if (locator == null || !locator.contains("heading:")) {
            return 1;
        }
        try {
            return Integer.parseInt(locator.substring(locator.lastIndexOf("heading:") + 8).replaceAll("\\D.*", ""));
        } catch (Exception ignored) {
            return 1;
        }
    }

    public record SectionDraft(String titlePath, List<DocumentElement> elements,
                               Integer pageStart, Integer pageEnd) {
    }
}
