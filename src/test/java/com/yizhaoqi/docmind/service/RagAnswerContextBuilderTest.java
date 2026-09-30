package com.yizhaoqi.docmind.service;

import com.yizhaoqi.docmind.entity.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagAnswerContextBuilderTest {

    @Test
    void shouldBuildNumberedPromptBlocksAndSourceMetadata() {
        SearchResult first = result(5201L, 7, "员工手册.pdf", "第二章｜考勤管理", 12, 12,
                "《员工手册》｜第二章｜考勤管理｜第 12 页", "员工应在上班前完成考勤打卡。");
        SearchResult second = result(5201L, 8, "员工手册.pdf", null, 13, 14,
                "《员工手册》｜第 13-14 页", "补卡需在三个工作日内提交申请。");

        RagAnswerContextBuilder.Context context = RagAnswerContextBuilder.build(List.of(first, second));

        assertThat(context.promptContext()).contains(
                "[来源#1]",
                "标题路径：第二章｜考勤管理",
                "页码：第 12 页",
                "[/来源#2]");
        assertThat(context.sources()).extracting(RagAnswerContextBuilder.Source::sourceId)
                .containsExactly(1, 2);
        assertThat(context.sources().get(0))
                .extracting(RagAnswerContextBuilder.Source::fileUploadId,
                        RagAnswerContextBuilder.Source::chunkOrdinal,
                        RagAnswerContextBuilder.Source::excerpt)
                .containsExactly(5201L, 7, "员工应在上班前完成考勤打卡。");
    }

    @Test
    void shouldReturnEmptyContextWhenSearchHasNoResult() {
        RagAnswerContextBuilder.Context context = RagAnswerContextBuilder.build(List.of());

        assertThat(context.promptContext()).isEmpty();
        assertThat(context.sources()).isEmpty();
    }

    private SearchResult result(Long fileUploadId, Integer chunkOrdinal, String fileName, String titlePath,
                                Integer pageStart, Integer pageEnd, String sourceLabel, String text) {
        SearchResult result = new SearchResult("file-md5", chunkOrdinal, text, 0.8, fileName);
        result.setFileUploadId(fileUploadId);
        result.setTitlePath(titlePath);
        result.setPageStart(pageStart);
        result.setPageEnd(pageEnd);
        result.setSourceLabel(sourceLabel);
        return result;
    }
}
