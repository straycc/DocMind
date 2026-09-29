package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.entity.DocumentElement;
import com.yizhaoqi.smartpai.model.DocumentElementType;
import com.yizhaoqi.smartpai.model.HeadingConfidence;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.xml.sax.SAXException;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** 将常见文本型文件解析成带来源定位的元素，其他文本型格式由 Tika 兜底。 */
public final class StructuredDocumentParser {
    private static final DocumentParser PDF_PARSER = new NativePdfParser();
    private static final Set<String> FORM_FIELD_WORDS = Set.of(
            "姓名", "部门", "日期", "申请人", "核准", "审核", "签字", "签章", "填表", "职务", "事由", "主管", "经理");

    private StructuredDocumentParser() {
    }

    public static ParseResult parse(Path path, String fileName) throws Exception {
        String extension = extensionOf(fileName);
        return switch (extension) {
            case "pdf" -> PDF_PARSER.parse(path);
            case "docx" -> parseDocx(path);
            case "pptx" -> parsePptx(path);
            case "xlsx", "xls" -> parseSpreadsheet(path);
            case "csv" -> parseCsv(path);
            case "md", "markdown" -> parseMarkdown(Files.readString(path));
            case "html", "htm" -> parseHtml(Files.readString(path));
            default -> parseWithTika(path);
        };
    }

    private static ParseResult parseDocx(Path path) throws Exception {
        List<DocumentElement> result = new ArrayList<>();
        int ordinal = 0;
        try (InputStream input = Files.newInputStream(path); XWPFDocument document = new XWPFDocument(input)) {
            for (IBodyElement bodyElement : document.getBodyElements()) {
                if (bodyElement instanceof XWPFParagraph paragraph) {
                    String text = TextCleaner.clean(paragraph.getText());
                    if (text.isEmpty()) {
                        continue;
                    }
                    String style = paragraph.getStyle();
                    Optional<HeadingDetector.DetectedHeading> inferredHeading = HeadingDetector.detect(text);
                    if (isHeadingStyle(style)) {
                        int level = extractHeadingLevel(style);
                        result.add(new DocumentElement(DocumentElementType.TITLE, text, null, "heading:" + level,
                                ++ordinal, level, HeadingConfidence.HIGH));
                    } else if (inferredHeading.isPresent()) {
                        HeadingDetector.DetectedHeading heading = inferredHeading.get();
                        result.add(new DocumentElement(DocumentElementType.TITLE, text, null,
                                "heading:" + heading.level(), ++ordinal, heading.level(), heading.confidence()));
                    } else if (!isFormScaffoldParagraph(text)) {
                        result.add(new DocumentElement(DocumentElementType.NARRATIVE, text, null,
                                "paragraph:" + (ordinal + 1), ++ordinal));
                    }
                } else if (bodyElement instanceof XWPFTable table) {
                    TableText tableText = extractTableText(table);
                    if (!tableText.text().isEmpty()) {
                        result.add(new DocumentElement(DocumentElementType.TABLE, tableText.text(), null,
                                "table:" + (ordinal + 1), ++ordinal));
                    }
                }
            }
        }
        return new ParseResult(result, false);
    }

    /**
     * Word 经常使用大量空单元格完成表单排版。检索只保留有实际文字的行；
     * 对高空白率的大型模板表直接跳过，避免空白考勤单、签字栏等污染向量库。
     */
    private static TableText extractTableText(XWPFTable table) {
        List<String> rows = new ArrayList<>();
        int totalCells = 0;
        int nonBlankCells = 0;
        for (XWPFTableRow row : table.getRows()) {
            List<String> values = new ArrayList<>();
            for (var cell : row.getTableCells()) {
                totalCells++;
                String value = TextCleaner.clean(cell.getText());
                if (!value.isEmpty()) {
                    values.add(value);
                    nonBlankCells++;
                }
            }
            if (!values.isEmpty()) {
                rows.add(String.join(" | ", values));
            }
        }
        String compactText = String.join("\n", rows);
        if (rows.isEmpty() || isBlankTemplate(totalCells, nonBlankCells, rows.size())
                || isFormTemplate(compactText)) {
            return new TableText("");
        }
        return new TableText(compactText);
    }

    private static boolean isBlankTemplate(int totalCells, int nonBlankCells, int nonBlankRows) {
        if (totalCells < 20 || nonBlankRows < 5) {
            return false;
        }
        return (double) nonBlankCells / totalCells < 0.30;
    }

    /** 仅过滤字段密集、没有制度数据的空白表单；金额、期限、审批矩阵等数据表仍会保留。 */
    private static boolean isFormTemplate(String tableText) {
        String normalized = TextCleaner.clean(tableText);
        if (containsAny(normalized, "申请单", "申请表", "签到卡", "签到簿", "请假卡", "通知单", "报销单", "日报表", "记录表", "汇总表")) {
            return true;
        }
        long fieldWords = FORM_FIELD_WORDS.stream().filter(normalized::contains).count();
        boolean datePlaceholder = normalized.matches("(?s).*(?:年\\s*月\\s*日|月\\s*日).*" );
        return fieldWords >= 5 || (fieldWords >= 3 && datePlaceholder);
    }

    private static boolean isFormScaffoldParagraph(String text) {
        if (text.length() > 100) {
            return false;
        }
        return containsAny(text, "申请单", "申请表", "签到卡", "签到簿", "请假卡", "通知单", "报销单", "日报表", "记录表", "汇总表")
                || (FORM_FIELD_WORDS.stream().filter(text::contains).count() >= 3
                && text.matches(".*(?:签字|签章|核准|审核|填表).*"));
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static ParseResult parsePptx(Path path) throws Exception {
        List<DocumentElement> result = new ArrayList<>();
        int ordinal = 0;
        try (InputStream input = Files.newInputStream(path); XMLSlideShow show = new XMLSlideShow(input)) {
            int page = 0;
            for (XSLFSlide slide : show.getSlides()) {
                page++;
                List<String> texts = new ArrayList<>();
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String value = TextCleaner.clean(textShape.getText());
                        if (!value.isEmpty()) {
                            texts.add(value);
                        }
                    }
                }
                if (texts.isEmpty()) {
                    continue;
                }
                String title = texts.remove(0);
                result.add(new DocumentElement(DocumentElementType.TITLE, title, page, "slide:" + page + ";heading:1",
                        ++ordinal, 1, HeadingConfidence.HIGH));
                if (!texts.isEmpty()) {
                    result.add(new DocumentElement(DocumentElementType.NARRATIVE, String.join("\n", texts), page,
                            "slide:" + page, ++ordinal));
                }
            }
        }
        return new ParseResult(result, false);
    }

    private static ParseResult parseSpreadsheet(Path path) throws Exception {
        List<DocumentElement> result = new ArrayList<>();
        int ordinal = 0;
        DataFormatter formatter = new DataFormatter();
        try (InputStream input = Files.newInputStream(path); Workbook workbook = WorkbookFactory.create(input)) {
            for (Sheet sheet : workbook) {
                result.add(new DocumentElement(DocumentElementType.TITLE, "工作表：" + sheet.getSheetName(), null,
                        "heading:1", ++ordinal, 1, HeadingConfidence.HIGH));
                StringBuilder table = new StringBuilder();
                for (Row row : sheet) {
                    List<String> values = new ArrayList<>();
                    for (Cell cell : row) {
                        values.add(formatter.formatCellValue(cell));
                    }
                    if (!values.stream().allMatch(String::isBlank)) {
                        table.append(String.join(" | ", values)).append('\n');
                    }
                }
                if (!table.isEmpty()) {
                    result.add(new DocumentElement(DocumentElementType.TABLE, table.toString().trim(), null,
                            "sheet:" + sheet.getSheetName(), ++ordinal));
                }
            }
        }
        return new ParseResult(result, false);
    }

    private static ParseResult parseCsv(Path path) throws Exception {
        String content = TextCleaner.clean(Files.readString(path));
        return new ParseResult(content.isEmpty() ? List.of() : List.of(new DocumentElement(DocumentElementType.TABLE,
                content, null, "csv", 1)), false);
    }

    private static ParseResult parseMarkdown(String content) {
        List<DocumentElement> result = new ArrayList<>();
        StringBuilder normal = new StringBuilder();
        StringBuilder code = new StringBuilder();
        boolean inCode = false;
        int ordinal = 0;
        for (String line : TextCleaner.clean(content).split("\\n")) {
            if (line.startsWith("```")) {
                if (inCode) {
                    result.add(new DocumentElement(DocumentElementType.CODE, code.toString().trim(), null, "code", ++ordinal));
                    code.setLength(0);
                }
                inCode = !inCode;
                continue;
            }
            if (inCode) {
                code.append(line).append('\n');
                continue;
            }
            if (line.matches("^#{1,6}\\s+.*")) {
                ordinal = flushNormal(result, normal, ordinal);
                int level = line.indexOf(' ');
                result.add(new DocumentElement(DocumentElementType.TITLE, line.substring(level + 1).trim(), null,
                        "heading:" + level, ++ordinal, level, HeadingConfidence.HIGH));
            } else if (line.matches("^[-*+]\\s+.*|^\\d+[.)]\\s+.*")) {
                ordinal = flushNormal(result, normal, ordinal);
                result.add(new DocumentElement(DocumentElementType.LIST, line, null, "list", ++ordinal));
            } else {
                normal.append(line).append('\n');
            }
        }
        flushNormal(result, normal, ordinal);
        return new ParseResult(result, false);
    }

    private static ParseResult parseHtml(String content) {
        List<DocumentElement> result = new ArrayList<>();
        int ordinal = 0;
        Elements elements = Jsoup.parse(content).select("h1,h2,h3,h4,h5,h6,p,li,pre,table");
        for (Element element : elements) {
            String text = TextCleaner.clean(element.text());
            if (text.isEmpty()) {
                continue;
            }
            String tag = element.tagName();
            DocumentElementType type = tag.matches("h[1-6]") ? DocumentElementType.TITLE
                    : "table".equals(tag) ? DocumentElementType.TABLE
                    : "pre".equals(tag) ? DocumentElementType.CODE
                    : "li".equals(tag) ? DocumentElementType.LIST : DocumentElementType.NARRATIVE;
            String locator = tag.matches("h[1-6]") ? "heading:" + tag.substring(1) : "html:" + tag;
            Integer headingLevel = tag.matches("h[1-6]") ? Integer.parseInt(tag.substring(1)) : null;
            result.add(new DocumentElement(type, text, null, locator, ++ordinal, headingLevel,
                    headingLevel == null ? null : HeadingConfidence.HIGH));
        }
        return new ParseResult(result, false);
    }

    private static ParseResult parseWithTika(Path path) throws Exception {
        BodyContentHandler handler = new BodyContentHandler(-1);
        try (InputStream input = Files.newInputStream(path)) {
            new AutoDetectParser().parse(input, handler, new Metadata(), new ParseContext());
        } catch (SAXException exception) {
            throw new IllegalArgumentException("Tika 无法解析此文件", exception);
        }
        List<DocumentElement> result = new ArrayList<>();
        int ordinal = 0;
        for (String paragraph : TextCleaner.clean(handler.toString()).split("\\n{2,}")) {
            addNarrative(result, paragraph, null, "tika", ++ordinal);
        }
        return new ParseResult(result, false);
    }

    private static void addNarrative(List<DocumentElement> result, String text, Integer page, String locator, int ordinal) {
        String normalized = TextCleaner.clean(text);
        if (!normalized.isEmpty()) {
            result.add(new DocumentElement(DocumentElementType.NARRATIVE, normalized, page, locator, ordinal));
        }
    }

    private static int flushNormal(List<DocumentElement> result, StringBuilder normal, int ordinal) {
        String text = TextCleaner.clean(normal.toString());
        normal.setLength(0);
        if (!text.isEmpty()) {
            result.add(new DocumentElement(DocumentElementType.NARRATIVE, text, null, "markdown", ++ordinal));
        }
        return ordinal;
    }

    private static int extractHeadingLevel(String style) {
        String digits = style.replaceAll("\\D+", "");
        return digits.isEmpty() ? 1 : Math.min(6, Integer.parseInt(digits));
    }

    private static boolean isHeadingStyle(String style) {
        return style != null && style.toLowerCase(Locale.ROOT).matches(".*(?:heading|标题)[ ]?[1-9].*");
    }

    private static String extensionOf(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    public record ParseResult(List<DocumentElement> elements, boolean needsOcr) {
    }

    private record TableText(String text) {
    }
}
