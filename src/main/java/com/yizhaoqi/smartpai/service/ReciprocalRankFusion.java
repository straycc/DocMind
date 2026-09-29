package com.yizhaoqi.smartpai.service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * 应用层 Reciprocal Rank Fusion（RRF）。
 *
 * <p>避免依赖 Elasticsearch 的商业许可证功能。每个候选项的最终分数为各路召回中
 * {@code 1 / (60 + rank)} 的累加；同一候选在同一路结果中仅按首次出现的排名计分。</p>
 */
public final class ReciprocalRankFusion {

    public static final int DEFAULT_RANK_CONSTANT = 60;

    private ReciprocalRankFusion() {
    }

    public static <T> List<ScoredItem<T>> fuse(List<T> keywordCandidates,
                                                List<T> vectorCandidates,
                                                Function<T, String> keyExtractor,
                                                int topK) {
        return fuse(keywordCandidates, vectorCandidates, keyExtractor, 1D, 1D, topK);
    }

    /**
     * 按给定通道权重执行 RRF。权重只影响两路排名的相对贡献，不使用不可比较的 ES 原始分数。
     */
    public static <T> List<ScoredItem<T>> fuse(List<T> keywordCandidates,
                                                List<T> vectorCandidates,
                                                Function<T, String> keyExtractor,
                                                double keywordWeight,
                                                double vectorWeight,
                                                int topK) {
        if (topK <= 0) {
            return List.of();
        }
        if (keywordWeight < 0 || vectorWeight < 0 || keywordWeight + vectorWeight <= 0) {
            throw new IllegalArgumentException("RRF 权重必须为非负数，且至少一个权重大于零");
        }

        Map<String, Candidate<T>> candidates = new LinkedHashMap<>();
        addCandidates(candidates, keywordCandidates, keyExtractor, keywordWeight);
        addCandidates(candidates, vectorCandidates, keyExtractor, vectorWeight);

        return candidates.values().stream()
                .sorted(Comparator.comparingDouble(Candidate<T>::score).reversed()
                        .thenComparingInt(Candidate<T>::firstSeenOrder)
                        .thenComparing(Candidate<T>::key))
                .limit(topK)
                .map(candidate -> new ScoredItem<>(candidate.item(), candidate.score()))
                .toList();
    }

    private static <T> void addCandidates(Map<String, Candidate<T>> candidates,
                                          List<T> rankedCandidates,
                                          Function<T, String> keyExtractor,
                                          double weight) {
        if (rankedCandidates == null || rankedCandidates.isEmpty()) {
            return;
        }

        int uniqueRank = 0;
        for (T item : rankedCandidates) {
            String key = Objects.requireNonNull(keyExtractor.apply(item), "RRF 候选项标识不能为空");
            Candidate<T> candidate = candidates.get(key);
            if (candidate != null && candidate.seenInCurrentList()) {
                continue;
            }
            uniqueRank++;
            if (candidate == null) {
                candidate = new Candidate<>(key, item, candidates.size());
                candidates.put(key, candidate);
            }
            candidate.addScore(weight / (DEFAULT_RANK_CONSTANT + uniqueRank));
            candidate.markSeenInCurrentList();
        }

        // 下一路召回要重新计算其内部排名。
        candidates.values().forEach(Candidate::clearSeenInCurrentList);
    }

    public record ScoredItem<T>(T item, double score) {
    }

    private static final class Candidate<T> {
        private final String key;
        private final T item;
        private final int firstSeenOrder;
        private double score;
        private boolean seenInCurrentList;

        private Candidate(String key, T item, int firstSeenOrder) {
            this.key = key;
            this.item = item;
            this.firstSeenOrder = firstSeenOrder;
        }

        private String key() {
            return key;
        }

        private T item() {
            return item;
        }

        private int firstSeenOrder() {
            return firstSeenOrder;
        }

        private double score() {
            return score;
        }

        private boolean seenInCurrentList() {
            return seenInCurrentList;
        }

        private void markSeenInCurrentList() {
            seenInCurrentList = true;
        }

        private void clearSeenInCurrentList() {
            seenInCurrentList = false;
        }

        private void addScore(double scoreToAdd) {
            score += scoreToAdd;
        }
    }
}
