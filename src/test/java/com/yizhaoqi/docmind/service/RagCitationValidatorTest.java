package com.yizhaoqi.docmind.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagCitationValidatorTest {

    @Test
    void shouldKeepOnlyExistingCitationIdsAsValid() {
        List<RagAnswerContextBuilder.Source> sources = List.of(
                source(1), source(2)
        );

        RagCitationValidator.Result result = RagCitationValidator.validate(
                "考勤需打卡【来源#1】，补卡有期限【来源#2】，不存在的资料【来源#9】。", sources);

        assertThat(result.citedSourceIds()).containsExactly(1, 2, 9);
        assertThat(result.invalidCitationIds()).containsExactly(9);
        assertThat(result.hasCitation()).isTrue();
        assertThat(result.allCitationIdsValid()).isFalse();
    }

    @Test
    void shouldAllowAnswerWithoutCitationButReportItsAbsence() {
        RagCitationValidator.Result result = RagCitationValidator.validate(
                "知识库中未找到足够依据。", List.of(source(1)));

        assertThat(result.citedSourceIds()).isEmpty();
        assertThat(result.invalidCitationIds()).isEmpty();
        assertThat(result.hasCitation()).isFalse();
        assertThat(result.allCitationIdsValid()).isTrue();
    }

    private RagAnswerContextBuilder.Source source(int sourceId) {
        return new RagAnswerContextBuilder.Source(sourceId, 1L, sourceId, "手册.pdf", null,
                null, null, "《手册》", "正文");
    }
}
