package com.yizhaoqi.docmind.service;

import com.yizhaoqi.docmind.entity.DocumentElement;
import com.yizhaoqi.docmind.model.DocumentElementType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentChunkerTest {

    @Test
    void shouldKeepForcedSplitChunksWithinHardLimitIncludingOverlapAndEmbeddingPrefix() {
        String fileName = "深圳市一个地球自然基金会员工手册.pdf";
        String titlePath = "标题".repeat(10);
        String longNarrative = "甲".repeat(1_200);
        DocumentElement element = new DocumentElement(DocumentElementType.NARRATIVE, longNarrative,
                1, "page:1", 1);
        String embeddingPrefix = DocumentEmbeddingTextBuilder.buildPrefix(fileName, titlePath);

        List<DocumentChunker.ChunkDraft> chunks = DocumentChunker.chunk(embeddingPrefix, List.of(element));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(CjkTokenEstimator.estimate(DocumentEmbeddingTextBuilder.build(fileName, titlePath, chunk.text())))
                        .isLessThanOrEqualTo(600));
        assertThat(chunks.get(1).text().replaceAll("\\s+", "")).startsWith("甲".repeat(64));
    }

    @Test
    void shouldMergeShortAdjacentTableAndNarrativeWithinHardLimit() {
        String prefix = DocumentEmbeddingTextBuilder.buildPrefix("制度.docx", "考勤管理");
        List<DocumentElement> elements = List.of(
                new DocumentElement(DocumentElementType.NARRATIVE, "考勤制度说明", null, "paragraph:1", 1),
                new DocumentElement(DocumentElementType.TABLE, "部门 | 人数\n研发部 | 12", null, "table:2", 2),
                new DocumentElement(DocumentElementType.NARRATIVE, "表格后的补充说明", null, "paragraph:3", 3)
        );

        List<DocumentChunker.ChunkDraft> chunks = DocumentChunker.chunk(prefix, elements);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).sourceLocator()).isEqualTo("paragraph:1,table:2,paragraph:3");
        assertThat(chunks.get(0).text()).contains("考勤制度说明", "部门 | 人数", "表格后的补充说明");
    }
}
