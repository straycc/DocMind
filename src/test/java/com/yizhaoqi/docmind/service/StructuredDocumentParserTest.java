package com.yizhaoqi.docmind.service;

import com.yizhaoqi.docmind.model.DocumentElementType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredDocumentParserTest {

    @Test
    void shouldRecognizeShortNumberedPdfHeadingLine() throws Exception {
        Path file = Files.createTempFile("docmind-parser-", ".pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            try (PDPageContentStream stream = new PDPageContentStream(document, document.getPage(0))) {
                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA, 12);
                stream.newLineAtOffset(72, 720);
                stream.showText("17 / 37");
                stream.newLineAtOffset(0, -24);
                stream.showText("1. Attendance Policy");
                stream.newLineAtOffset(0, -24);
                stream.showText("(1) Sign offer letter");
                stream.newLineAtOffset(0, -24);
                stream.showText("1.1.1 Deep numbered item");
                stream.newLineAtOffset(0, -24);
                stream.showText("Employees must submit requests through the system.");
                stream.endText();
            }
            document.save(file.toFile());
        }
        try {
            StructuredDocumentParser.ParseResult parsed = StructuredDocumentParser.parse(file, "manual.pdf");
            assertThat(parsed.needsOcr()).isFalse();
            List<String> titles = parsed.elements().stream()
                    .filter(element -> element.getType() == DocumentElementType.TITLE)
                    .map(element -> element.getText())
                    .toList();
            assertThat(titles).contains("1. Attendance Policy");
            assertThat(titles).doesNotContain("17 / 37", "(1) Sign offer letter", "1.1.1 Deep numbered item");
            assertThat(parsed.elements()).extracting(element -> element.getText())
                    .noneMatch("17 / 37"::equals)
                    .anyMatch(text -> text.contains("(1) Sign offer letter"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void shouldSkipTableOfContentsPageBeforeHeadingRecognition() throws Exception {
        Path file = Files.createTempFile("docmind-contents-", ".pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.addPage(new PDPage());
            writePage(document, 0, List.of(
                    "Contents",
                    "1. Attendance Policy ............ 5",
                    "2. Leave Policy ................. 9"));
            writePage(document, 1, List.of(
                    "1. Attendance Policy",
                    "Employees must submit requests through the system."));
            document.save(file.toFile());
        }
        try {
            StructuredDocumentParser.ParseResult parsed = StructuredDocumentParser.parse(file, "manual.pdf");

            assertThat(parsed.elements()).extracting(element -> element.getText())
                    .noneMatch(text -> text.contains("Contents") || text.contains("............"));
            assertThat(parsed.elements())
                    .filteredOn(element -> element.getType() == DocumentElementType.TITLE)
                    .extracting(element -> element.getText())
                    .containsExactly("1. Attendance Policy");
            assertThat(parsed.elements()).allSatisfy(element -> assertThat(element.getPageNumber()).isEqualTo(2));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void shouldKeepMeaningfulDocxTableAndSkipSparseBlankTemplate() throws Exception {
        Path file = Files.createTempFile("docmind-docx-table-", ".docx");
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFTable meaningful = document.createTable(3, 2);
            setCell(meaningful, 0, 0, "部门");
            setCell(meaningful, 0, 1, "人数");
            setCell(meaningful, 1, 0, "研发部");
            setCell(meaningful, 1, 1, "12");
            setCell(meaningful, 2, 0, "人事部");
            setCell(meaningful, 2, 1, "3");

            XWPFTable blankTemplate = document.createTable(6, 4);
            for (int row = 0; row < 6; row++) {
                setCell(blankTemplate, row, 0, "日期" + row);
            }
            document.createParagraph().createRun().setText("第二章 考勤管理");
            document.createParagraph().createRun().setText("考勤规则正文");
            XWPFParagraph styledHeading = document.createParagraph();
            styledHeading.setStyle("标题 1");
            styledHeading.createRun().setText("适用范围");
            XWPFTable formTemplate = document.createTable(3, 3);
            setCell(formTemplate, 0, 0, "出差申请单");
            setCell(formTemplate, 0, 1, "日期");
            setCell(formTemplate, 1, 0, "姓名");
            setCell(formTemplate, 1, 1, "部门");
            setCell(formTemplate, 2, 0, "申请人");
            setCell(formTemplate, 2, 1, "核准");
            try (var output = Files.newOutputStream(file)) {
                document.write(output);
            }
        }
        try {
            StructuredDocumentParser.ParseResult parsed = StructuredDocumentParser.parse(file, "forms.docx");

            assertThat(parsed.elements())
                    .filteredOn(element -> element.getType() == DocumentElementType.TABLE)
                    .extracting(element -> element.getText())
                    .containsExactly("部门 | 人数\n研发部 | 12\n人事部 | 3");
            assertThat(parsed.elements())
                    .filteredOn(element -> element.getType() == DocumentElementType.TITLE)
                    .extracting(element -> element.getText())
                    .contains("第二章 考勤管理", "适用范围");
            assertThat(parsed.elements()).extracting(element -> element.getText())
                    .contains("考勤规则正文")
                    .doesNotContain("出差申请单");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private void writePage(PDDocument document, int pageIndex, List<String> lines) throws Exception {
        try (PDPageContentStream stream = new PDPageContentStream(document, document.getPage(pageIndex))) {
            stream.beginText();
            stream.setFont(PDType1Font.HELVETICA, 12);
            stream.newLineAtOffset(72, 720);
            for (String line : lines) {
                stream.showText(line);
                stream.newLineAtOffset(0, -24);
            }
            stream.endText();
        }
    }

    private void setCell(XWPFTable table, int row, int column, String text) {
        table.getRow(row).getCell(column).setText(text);
    }
}
