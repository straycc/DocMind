package com.yizhaoqi.docmind.service;

import com.yizhaoqi.docmind.entity.DocumentElement;
import com.yizhaoqi.docmind.model.DocumentElementType;
import com.yizhaoqi.docmind.model.HeadingConfidence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentSectionPlannerTest {

    @Test
    void shouldBuildFlatSectionsFromActualHeadingHierarchy() {
        List<DocumentSectionPlanner.SectionDraft> sections = DocumentSectionPlanner.plan(List.of(
                element(DocumentElementType.TITLE, "第一章 总则", null, "heading:1", 1),
                element(DocumentElementType.NARRATIVE, "第一章正文", null, "paragraph:2", 2),
                element(DocumentElementType.TITLE, "1.1 适用范围", null, "heading:2", 3),
                element(DocumentElementType.NARRATIVE, "适用范围正文", null, "paragraph:4", 4),
                element(DocumentElementType.TITLE, "第二章 考勤", null, "heading:1", 5),
                element(DocumentElementType.NARRATIVE, "考勤正文", null, "paragraph:6", 6)
        ));

        assertThat(sections).extracting(DocumentSectionPlanner.SectionDraft::titlePath)
                .containsExactly("第一章 总则", "第一章 总则｜1.1 适用范围", "第二章 考勤");
        assertThat(sections.get(0).elements()).extracting(DocumentElement::getText).containsExactly("第一章正文");
    }

    @Test
    void shouldPreferPageBoundaryForFallbackSections() {
        String page = "甲".repeat(1_000);
        List<DocumentSectionPlanner.SectionDraft> sections = DocumentSectionPlanner.plan(List.of(
                element(DocumentElementType.NARRATIVE, page, 1, "page:1", 1),
                element(DocumentElementType.NARRATIVE, page, 2, "page:2", 2),
                element(DocumentElementType.NARRATIVE, page, 3, "page:3", 3)
        ));

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).titlePath()).isNull();
        assertThat(sections.get(0).pageStart()).isEqualTo(1);
        assertThat(sections.get(0).pageEnd()).isEqualTo(2);
        assertThat(sections.get(1).pageStart()).isEqualTo(3);
    }

    @Test
    void shouldFallbackToNaturalElementsWithoutPageNumbers() {
        String paragraph = "甲".repeat(1_000);
        List<DocumentSectionPlanner.SectionDraft> sections = DocumentSectionPlanner.plan(List.of(
                element(DocumentElementType.NARRATIVE, paragraph, null, "tika", 1),
                element(DocumentElementType.LIST, paragraph, null, "tika", 2),
                element(DocumentElementType.TABLE, paragraph, null, "tika", 3)
        ));

        assertThat(sections).hasSize(3);
        assertThat(sections).allSatisfy(section -> assertThat(section.titlePath()).isNull());
    }

    @Test
    void shouldKeepShortUntitledDocumentInOneSection() {
        List<DocumentSectionPlanner.SectionDraft> sections = DocumentSectionPlanner.plan(List.of(
                element(DocumentElementType.NARRATIVE, "简短的第一段", null, "tika", 1),
                element(DocumentElementType.NARRATIVE, "简短的第二段", null, "tika", 2)
        ));

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).titlePath()).isNull();
    }

    @Test
    void shouldKeepDeepHeadingInChunkInsteadOfCreatingAnotherSection() {
        DocumentElement chapter = new DocumentElement(DocumentElementType.TITLE, "1. 招聘管理", 1,
                "heading:1", 1, 1, HeadingConfidence.MEDIUM);
        DocumentElement deepHeading = new DocumentElement(DocumentElementType.TITLE, "1.1.1 签署聘用信", 1,
                "heading:3", 2, 3, HeadingConfidence.LOW);
        List<DocumentSectionPlanner.SectionDraft> sections = DocumentSectionPlanner.plan(List.of(
                chapter,
                element(DocumentElementType.NARRATIVE, "招聘正文", 1, "page:1", 2),
                deepHeading,
                element(DocumentElementType.NARRATIVE, "签署正文", 1, "page:1", 3)
        ));

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).titlePath()).isEqualTo("1. 招聘管理");
        assertThat(sections.get(0).elements()).extracting(DocumentElement::getText)
                .contains("1.1.1 签署聘用信", "签署正文");
    }

    @Test
    void shouldKeepArticleHeadingsWithinTheChapterInsteadOfCreatingTinySections() {
        DocumentElement chapter = new DocumentElement(DocumentElementType.TITLE, "第一章 总则", null,
                "heading:1", 1, 1, HeadingConfidence.MEDIUM);
        DocumentElement articleOne = new DocumentElement(DocumentElementType.TITLE, "第一条 目的", null,
                "heading:3", 2, 3, HeadingConfidence.LOW);
        DocumentElement articleTwo = new DocumentElement(DocumentElementType.TITLE, "第二条 范围", null,
                "heading:3", 4, 3, HeadingConfidence.LOW);

        List<DocumentSectionPlanner.SectionDraft> sections = DocumentSectionPlanner.plan(List.of(
                chapter,
                articleOne,
                element(DocumentElementType.NARRATIVE, "第一条正文", null, "paragraph:3", 3),
                articleTwo,
                element(DocumentElementType.NARRATIVE, "第二条正文", null, "paragraph:5", 5)
        ));

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).titlePath()).isEqualTo("第一章 总则");
        assertThat(sections.get(0).elements()).extracting(DocumentElement::getText)
                .contains("第一条 目的", "第一条正文", "第二条 范围", "第二条正文");
    }

    @Test
    void shouldClassifyArticleAsNonSectionHeading() {
        HeadingDetector.DetectedHeading heading = HeadingDetector.detect("第一条 目的").orElseThrow();

        assertThat(heading.level()).isEqualTo(3);
        assertThat(heading.confidence()).isEqualTo(HeadingConfidence.LOW);
    }

    @Test
    void shouldBuildEmbeddingTextWithoutFileExtension() {
        assertThat(DocumentEmbeddingTextBuilder.build("员工手册.pdf", "第二章｜考勤", "正文"))
                .isEqualTo("员工手册\n第二章|考勤\n正文");
    }

    @Test
    void shouldFormatVerifiableFallbackAndHeadingSources() {
        assertThat(SourceLabelFormatter.format("员工手册.pdf", "第二章｜考勤管理", 12, 14, "正文"))
                .isEqualTo("《员工手册》｜第二章｜考勤管理｜第 12-14 页");
        assertThat(SourceLabelFormatter.format("员工手册.pdf", null, null, null, "这是没有页码的原文正文"))
                .isEqualTo("《员工手册》｜原文片段：“这是没有页码的原文正文”");
    }

    private DocumentElement element(DocumentElementType type, String text, Integer page, String locator, int ordinal) {
        return new DocumentElement(type, text, page, locator, ordinal);
    }
}
