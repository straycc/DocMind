package com.yizhaoqi.smartpai.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 校验模型输出中的来源编号是否属于当前轮检索结果。
 * 该校验只验证编号存在性，不把“编号存在”误当作事实已被语义证明。
 */
public final class RagCitationValidator {

    private static final Pattern CITATION_PATTERN = Pattern.compile("【来源#\\s*(\\d+)】");

    private RagCitationValidator() {
    }

    public static Result validate(String answer, List<RagAnswerContextBuilder.Source> sources) {
        Set<Integer> availableIds = new LinkedHashSet<>();
        if (sources != null) {
            sources.forEach(source -> availableIds.add(source.sourceId()));
        }

        Set<Integer> citedIds = new LinkedHashSet<>();
        Set<Integer> invalidIds = new LinkedHashSet<>();
        Matcher matcher = CITATION_PATTERN.matcher(answer == null ? "" : answer);
        while (matcher.find()) {
            int sourceId = Integer.parseInt(matcher.group(1));
            citedIds.add(sourceId);
            if (!availableIds.contains(sourceId)) {
                invalidIds.add(sourceId);
            }
        }

        return new Result(
                new ArrayList<>(citedIds),
                new ArrayList<>(invalidIds),
                !citedIds.isEmpty(),
                invalidIds.isEmpty()
        );
    }

    public record Result(
            List<Integer> citedSourceIds,
            List<Integer> invalidCitationIds,
            boolean hasCitation,
            boolean allCitationIdsValid
    ) {
        public Result {
            citedSourceIds = List.copyOf(citedSourceIds);
            invalidCitationIds = List.copyOf(invalidCitationIds);
        }
    }
}
