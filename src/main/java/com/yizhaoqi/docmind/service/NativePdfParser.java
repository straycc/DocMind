package com.yizhaoqi.docmind.service;

import com.yizhaoqi.docmind.entity.DocumentElement;
import com.yizhaoqi.docmind.model.DocumentElementType;
import com.yizhaoqi.docmind.model.HeadingConfidence;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.text.PDFTextStripper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 基于 PDFBox 的原生 PDF 解析器。
 *
 * <p>它负责产生结构候选，不直接决定 Section 边界；标题可信度由下游规划器使用。</p>
 */
public final class NativePdfParser implements DocumentParser {
    private static final Pattern PAGE_COUNTER = Pattern.compile("^\\s*(?:第\\s*)?\\d+\\s*[/／]\\s*\\d+\\s*(?:页)?\\s*$");
    private static final Pattern CONTENTS_HEADING = Pattern.compile("(?i)^(?:目\\s*录|contents?)$");
    private static final Pattern DOTTED_LEADER = Pattern.compile("(?:[.．·…]\\s*){6,}");

    @Override
    public StructuredDocumentParser.ParseResult parse(Path path) throws Exception {
        try (PDDocument document = PDDocument.load(path.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            List<String> pages = new ArrayList<>();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                pages.add(stripper.getText(document));
            }
            pages = TextCleaner.removeRepeatedPageChrome(pages);
            int characters = pages.stream().mapToInt(value -> TextCleaner.clean(value).length()).sum();
            if (document.getNumberOfPages() > 0 && characters < document.getNumberOfPages() * 10) {
                return new StructuredDocumentParser.ParseResult(List.of(), true);
            }

            List<DocumentElement> result = new ArrayList<>();
            Map<Integer, List<PdfHeading>> outlineHeadings = extractOutlineHeadings(document);
            int ordinal = 0;
            for (int page = 0; page < pages.size(); page++) {
                ordinal = addPageElements(result, TextCleaner.clean(pages.get(page)), page + 1,
                        outlineHeadings.getOrDefault(page + 1, List.of()), ordinal);
            }
            return new StructuredDocumentParser.ParseResult(result, false);
        }
    }

    private int addPageElements(List<DocumentElement> result, String pageText, int page,
                                List<PdfHeading> outlines, int ordinal) {
        // 目录中的标题和页码只是导航信息，进入检索会污染真实标题路径和召回结果。
        if (isTableOfContentsPage(pageText)) {
            return ordinal;
        }

        Set<String> outlineTitles = new HashSet<>();
        for (PdfHeading outline : outlines) {
            String title = TextCleaner.clean(outline.title());
            if (!title.isEmpty()) {
                result.add(titleElement(title, page, ++ordinal, outline.level(), HeadingConfidence.HIGH));
                outlineTitles.add(title);
            }
        }

        StringBuilder paragraph = new StringBuilder();
        for (String rawLine : pageText.split("\\n", -1)) {
            String line = TextCleaner.clean(rawLine);
            if (line.isEmpty()) {
                ordinal = flushParagraph(result, paragraph, page, ordinal);
                continue;
            }
            if (PAGE_COUNTER.matcher(line).matches()) {
                // 每页变化的“当前页 / 总页数”不能作为标题或正文进入检索。
                ordinal = flushParagraph(result, paragraph, page, ordinal);
                continue;
            }

            var candidate = HeadingDetector.detect(line);
            if (candidate.isPresent()) {
                ordinal = flushParagraph(result, paragraph, page, ordinal);
                if (!outlineTitles.contains(line)) {
                    result.add(titleElement(line, page, ++ordinal, candidate.get().level(), candidate.get().confidence()));
                }
                continue;
            }

            // 低可信编号、列表和数值行不丢失，保留到正文并与相邻段落一起参与 Token Chunk。
            appendLine(paragraph, line);
        }
        return flushParagraph(result, paragraph, page, ordinal);
    }

    /**
     * 目录页以“目录/Contents”与点引导符为主要特征。没有目录标题时，至少三行点引导符才跳过，
     * 避免把普通正文中的单条省略号或表格行误删。
     */
    private boolean isTableOfContentsPage(String pageText) {
        boolean hasContentsHeading = false;
        int dottedLeaderLines = 0;
        for (String rawLine : pageText.split("\\n", -1)) {
            String line = TextCleaner.clean(rawLine);
            if (line.isEmpty()) {
                continue;
            }
            if (CONTENTS_HEADING.matcher(line).matches()) {
                hasContentsHeading = true;
            }
            if (DOTTED_LEADER.matcher(line).find()) {
                dottedLeaderLines++;
            }
        }
        return (hasContentsHeading && dottedLeaderLines >= 2) || dottedLeaderLines >= 3;
    }

    private DocumentElement titleElement(String text, int page, int ordinal, int level,
                                         HeadingConfidence confidence) {
        return new DocumentElement(DocumentElementType.TITLE, text, page,
                "page:" + page + ";heading:" + level + ";confidence:" + confidence.name(), ordinal, level, confidence);
    }

    private void appendLine(StringBuilder paragraph, String line) {
        if (!paragraph.isEmpty()) {
            paragraph.append('\n');
        }
        paragraph.append(line);
    }

    private int flushParagraph(List<DocumentElement> result, StringBuilder paragraph, int page, int ordinal) {
        String text = TextCleaner.clean(paragraph.toString());
        paragraph.setLength(0);
        if (!text.isEmpty()) {
            result.add(new DocumentElement(DocumentElementType.NARRATIVE, text, page, "page:" + page, ++ordinal));
        }
        return ordinal;
    }

    private Map<Integer, List<PdfHeading>> extractOutlineHeadings(PDDocument document) {
        Map<Integer, List<PdfHeading>> headings = new HashMap<>();
        PDDocumentOutline outline = document.getDocumentCatalog().getDocumentOutline();
        if (outline != null) {
            collectOutlineHeadings(document, outline.getFirstChild(), 1, headings);
        }
        return headings;
    }

    private void collectOutlineHeadings(PDDocument document, PDOutlineItem item, int level,
                                        Map<Integer, List<PdfHeading>> headings) {
        for (PDOutlineItem current = item; current != null; current = current.getNextSibling()) {
            PDPage destination = null;
            try {
                destination = current.findDestinationPage(document);
            } catch (Exception ignored) {
                // 单个损坏书签不影响正文解析。
            }
            int page = pageNumber(document, destination);
            String title = TextCleaner.clean(current.getTitle());
            if (page > 0 && !title.isEmpty()) {
                headings.computeIfAbsent(page, ignored -> new ArrayList<>()).add(new PdfHeading(title, level));
            }
            collectOutlineHeadings(document, current.getFirstChild(), Math.min(6, level + 1), headings);
        }
    }

    private int pageNumber(PDDocument document, PDPage target) {
        if (target == null) {
            return -1;
        }
        int number = 0;
        for (PDPage page : document.getPages()) {
            number++;
            if (page.getCOSObject().equals(target.getCOSObject())) {
                return number;
            }
        }
        return -1;
    }

    private record PdfHeading(String title, int level) {
    }

}
