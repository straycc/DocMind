package com.yizhaoqi.smartpai.evaluation;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.CountResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.client.RerankerClient;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.service.ReciprocalRankFusion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SciFact 检索集成评测。
 *
 * <p>此测试会调用在线 Embedding API，并查询已导入的独立评测索引；它不创建或修改业务索引。
 * 类名以 IT 结尾，因此默认的 {@code mvn test} 不会自动执行。需要时显式执行：
 * {@code mvn "-Dtest=SciFactRetrievalIT" test}。</p>
 */
@Tag("external")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(properties = {
        "rag-eval.scifact.import-enabled=false",
        "document.reindex-all=false",
        "elasticsearch.index-initializer.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.task.scheduling.enabled=false"
})
class SciFactRetrievalIT {

    private static final int TOP_K = 10;
    private static final int VECTOR_CANDIDATES = 100;
    private static final String RERANK_METHOD = "qwen3_rerank";
    private static final List<FusionVariant> FUSION_VARIANTS = List.of(
            new FusionVariant("rrf_equal", 1D, 1D),
            new FusionVariant("rrf_vector_70", 0.3D, 0.7D),
            new FusionVariant("rrf_vector_90", 0.1D, 0.9D)
    );

    private final ElasticsearchClient elasticsearchClient;
    private final EmbeddingClient embeddingClient;
    private final ObjectMapper objectMapper;
    private final String indexName;
    private final Path manifestPath;
    private final Path resultPath;
    private final RerankerClient rerankerClient;
    private final int rerankCandidateLimit;

    @Autowired
    SciFactRetrievalIT(ElasticsearchClient elasticsearchClient,
                       EmbeddingClient embeddingClient,
                       ObjectMapper objectMapper,
                       ObjectProvider<RerankerClient> rerankerClientProvider,
                       @Value("${rag-eval.scifact.index:rag_eval_scifact_dev_v1}") String indexName,
                       @Value("${rag-eval.scifact.report-file:rag-eval-results/scifact-dev-manifest.json}") String manifestFile,
                       @Value("${rag-eval.scifact.evaluation-report-file:rag-eval-results/scifact-retrieval-report.json}") String resultFile,
                       @Value("${rerank.api.candidate-limit:30}") int rerankCandidateLimit) {
        this.elasticsearchClient = elasticsearchClient;
        this.embeddingClient = embeddingClient;
        this.objectMapper = objectMapper;
        this.indexName = indexName;
        this.manifestPath = Path.of(manifestFile);
        this.resultPath = Path.of(resultFile);
        this.rerankerClient = rerankerClientProvider.getIfAvailable();
        this.rerankCandidateLimit = rerankCandidateLimit;
    }

    @Test
    void shouldEvaluateBm25VectorAndRrfRetrieval() throws Exception {
        EvaluationManifest manifest = readManifest();
        assertThat(manifest.claims()).isNotEmpty();
        assertThat(manifest.esIndex()).isEqualTo(indexName);

        CountResponse count = elasticsearchClient.count(c -> c.index(indexName));
        assertThat(count.count())
                .as("评测索引 %s 不存在或没有导入文档，请先运行 SciFact 导入任务", indexName)
                .isPositive();

        List<String> queries = manifest.claims().stream().map(EvaluationClaim::claim).toList();
        List<float[]> vectors = embeddingClient.embed(queries);
        assertThat(vectors)
                .as("Embedding 返回数量必须与评测问题数量一致")
                .hasSize(manifest.claims().size());

        Map<String, MethodAccumulator> accumulators = new LinkedHashMap<>();
        accumulators.put("bm25", new MethodAccumulator());
        accumulators.put("vector", new MethodAccumulator());
        FUSION_VARIANTS.forEach(variant -> accumulators.put(variant.name(), new MethodAccumulator()));
        if (rerankerClient != null) {
            accumulators.put(RERANK_METHOD, new MethodAccumulator());
        }

        for (int i = 0; i < manifest.claims().size(); i++) {
            EvaluationClaim claim = manifest.claims().get(i);
            Set<String> expectedLocators = claim.evidenceDocumentIds().stream()
                    .map(documentId -> "scifact:" + documentId)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            List<Float> queryVector = toFloatList(vectors.get(i));
            List<RetrievalCandidate> keywordCandidates = bm25(claim.claim(), VECTOR_CANDIDATES);
            List<RetrievalCandidate> vectorCandidates = vector(queryVector, VECTOR_CANDIDATES);

            evaluate(accumulators.get("bm25"), claim, expectedLocators, sourceLocators(topK(keywordCandidates)));
            evaluate(accumulators.get("vector"), claim, expectedLocators, sourceLocators(topK(vectorCandidates)));
            FUSION_VARIANTS.forEach(variant -> evaluate(accumulators.get(variant.name()), claim,
                    expectedLocators, sourceLocators(rrf(keywordCandidates, vectorCandidates, variant, TOP_K))));
            if (rerankerClient != null) {
                List<RetrievalCandidate> rerankCandidates = rrf(keywordCandidates, vectorCandidates,
                        vectorDominantVariant(), rerankCandidateLimit);
                evaluate(accumulators.get(RERANK_METHOD), claim, expectedLocators,
                        sourceLocators(rerank(claim.claim(), rerankCandidates)));
            }
        }

        EvaluationReport report = new EvaluationReport(
                "SciFact dev retrieval evaluation",
                OffsetDateTime.now().toString(),
                indexName,
                manifestPath.toString(),
                manifest.claims().size(),
                TOP_K,
                FUSION_VARIANTS.stream().collect(java.util.stream.Collectors.toMap(
                        FusionVariant::name,
                        java.util.function.Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new)),
                accumulators.entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                Map.Entry::getKey,
                                entry -> entry.getValue().toReport(),
                                (left, right) -> left,
                                LinkedHashMap::new)));
        writeReport(report);
    }

    private EvaluationManifest readManifest() throws IOException {
        assertThat(Files.isRegularFile(manifestPath))
                .as("找不到评测标注文件：%s，请先执行 SciFact 导入", manifestPath.toAbsolutePath())
                .isTrue();
        return objectMapper.readValue(manifestPath.toFile(), EvaluationManifest.class);
    }

    private List<RetrievalCandidate> bm25(String query, int size) throws IOException {
        SearchResponse<EsDocument> response = elasticsearchClient.search(s -> s
                        .index(indexName)
                        .size(size)
                        .query(q -> q.match(m -> m.field("textContent").query(query))),
                EsDocument.class);
        return retrievalCandidates(response);
    }

    private List<RetrievalCandidate> vector(List<Float> queryVector, int size) throws IOException {
        SearchResponse<EsDocument> response = elasticsearchClient.search(s -> s
                        .index(indexName)
                        .size(size)
                        .knn(knn -> knn.field("vector")
                                .queryVector(queryVector)
                                .k(size)
                                .numCandidates(VECTOR_CANDIDATES)),
                EsDocument.class);
        return retrievalCandidates(response);
    }

    private List<RetrievalCandidate> rrf(List<RetrievalCandidate> keywordCandidates,
                                         List<RetrievalCandidate> vectorCandidates,
                                         FusionVariant variant, int topN) {
        return ReciprocalRankFusion.fuse(keywordCandidates, vectorCandidates,
                        RetrievalCandidate::sourceLocator, variant.keywordWeight(), variant.vectorWeight(), topN)
                .stream()
                .map(ReciprocalRankFusion.ScoredItem::item)
                .toList();
    }

    private List<RetrievalCandidate> topK(List<RetrievalCandidate> candidates) {
        return candidates.stream().limit(TOP_K).toList();
    }

    private List<RetrievalCandidate> retrievalCandidates(SearchResponse<EsDocument> response) {
        Map<String, RetrievalCandidate> distinct = new LinkedHashMap<>();
        response.hits().hits().forEach(hit -> {
            EsDocument document = hit.source();
            if (document == null || document.getSourceLocator() == null) {
                return;
            }
            String titlePath = document.getTitlePath() == null ? "" : document.getTitlePath() + "\n";
            distinct.putIfAbsent(document.getSourceLocator(), new RetrievalCandidate(document.getSourceLocator(),
                    titlePath + document.getTextContent()));
        });
        return List.copyOf(distinct.values());
    }

    private List<String> sourceLocators(List<RetrievalCandidate> candidates) {
        return candidates.stream().map(RetrievalCandidate::sourceLocator).toList();
    }

    private List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates) {
        List<RerankerClient.RankedDocument> ranked = rerankerClient.rerank(query,
                candidates.stream().map(RetrievalCandidate::rerankText).toList(), TOP_K);
        return ranked.stream().map(rank -> candidates.get(rank.index())).toList();
    }

    private FusionVariant vectorDominantVariant() {
        return FUSION_VARIANTS.stream()
                .filter(variant -> variant.name().equals("rrf_vector_90"))
                .findFirst()
                .orElseThrow();
    }

    private void evaluate(MethodAccumulator accumulator, EvaluationClaim claim,
                          Set<String> expectedLocators, List<String> retrievedLocators) {
        int rank = firstRelevantRank(expectedLocators, retrievedLocators);
        accumulator.add(new ClaimResult(
                claim.id(),
                claim.claim(),
                expectedLocators,
                retrievedLocators,
                rank));
    }

    private int firstRelevantRank(Set<String> expectedLocators, List<String> retrievedLocators) {
        for (int i = 0; i < retrievedLocators.size(); i++) {
            if (expectedLocators.contains(retrievedLocators.get(i))) {
                return i + 1;
            }
        }
        return 0;
    }

    private List<Float> toFloatList(float[] vector) {
        List<Float> result = new ArrayList<>(vector.length);
        for (float value : vector) {
            result.add(value);
        }
        return result;
    }

    private void writeReport(EvaluationReport report) throws IOException {
        Path parent = resultPath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(resultPath.toFile(), report);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EvaluationManifest(String dataset, int claimCount, int documentCount,
                              String esIndex, List<EvaluationClaim> claims) {
    }

    record EvaluationClaim(long id, String claim, List<String> evidenceDocumentIds) {
    }

    record EvaluationReport(String dataset, String generatedAt, String index,
                            String manifestFile, int claimCount, int topK,
                            Map<String, FusionVariant> fusionVariants,
                            Map<String, MethodReport> methods) {
    }

    record MethodReport(double hitRateAt1, double hitRateAt3, double hitRateAt5, double hitRateAt10,
                        double recallAt1, double recallAt3, double recallAt5, double recallAt10,
                        double precisionAt1, double precisionAt3, double precisionAt5, double precisionAt10,
                        double ndcgAt1, double ndcgAt3, double ndcgAt5, double ndcgAt10,
                        double mrrAt10, List<ClaimResult> claims) {
    }

    record ClaimResult(long claimId, String claim, Collection<String> expectedSourceLocators,
                       List<String> retrievedSourceLocators, int firstRelevantRank) {
    }

    /** 用同一批召回候选比较不同 RRF 通道权重。 */
    record FusionVariant(String name, double keywordWeight, double vectorWeight) {
    }

    /** 同一篇 SciFact 论文仅保留最高排名的 chunk，按论文级别计算检索指标。 */
    record RetrievalCandidate(String sourceLocator, String rerankText) {
    }

    static final class MethodAccumulator {
        private final List<ClaimResult> results = new ArrayList<>();

        void add(ClaimResult result) {
            results.add(result);
        }

        MethodReport toReport() {
            return new MethodReport(
                    hitRateAt(1),
                    hitRateAt(3),
                    hitRateAt(5),
                    hitRateAt(10),
                    recallAt(1),
                    recallAt(3),
                    recallAt(5),
                    recallAt(10),
                    precisionAt(1),
                    precisionAt(3),
                    precisionAt(5),
                    precisionAt(10),
                    ndcgAt(1),
                    ndcgAt(3),
                    ndcgAt(5),
                    ndcgAt(10),
                    mrrAt10(),
                    List.copyOf(results));
        }

        private double hitRateAt(int k) {
            return results.stream().filter(result -> result.firstRelevantRank() > 0
                    && result.firstRelevantRank() <= k).count() / (double) results.size();
        }

        private double recallAt(int k) {
            return results.stream().mapToDouble(result -> {
                Set<String> relevant = new LinkedHashSet<>(result.expectedSourceLocators());
                if (relevant.isEmpty()) {
                    return 0D;
                }
                return relevantRetrievedAt(result, relevant, k) / (double) relevant.size();
            }).average().orElse(0D);
        }

        private double precisionAt(int k) {
            return results.stream().mapToDouble(result -> {
                Set<String> relevant = new LinkedHashSet<>(result.expectedSourceLocators());
                return relevantRetrievedAt(result, relevant, k) / (double) k;
            }).average().orElse(0D);
        }

        private double ndcgAt(int k) {
            return results.stream().mapToDouble(result -> {
                Set<String> relevant = new LinkedHashSet<>(result.expectedSourceLocators());
                if (relevant.isEmpty()) {
                    return 0D;
                }
                double dcg = 0D;
                int limit = Math.min(k, result.retrievedSourceLocators().size());
                Set<String> credited = new LinkedHashSet<>();
                for (int index = 0; index < limit; index++) {
                    String locator = result.retrievedSourceLocators().get(index);
                    if (relevant.contains(locator) && credited.add(locator)) {
                        dcg += discount(index + 1);
                    }
                }
                double idcg = 0D;
                for (int rank = 1; rank <= Math.min(k, relevant.size()); rank++) {
                    idcg += discount(rank);
                }
                return idcg == 0D ? 0D : dcg / idcg;
            }).average().orElse(0D);
        }

        private long relevantRetrievedAt(ClaimResult result, Set<String> relevant, int k) {
            return result.retrievedSourceLocators().stream()
                    .limit(k)
                    .filter(relevant::contains)
                    .distinct()
                    .count();
        }

        private double discount(int rank) {
            return 1D / (Math.log(rank + 1D) / Math.log(2D));
        }

        private double mrrAt10() {
            return results.stream().mapToDouble(result -> result.firstRelevantRank() == 0
                    || result.firstRelevantRank() > 10
                    ? 0D : 1D / result.firstRelevantRank()).average().orElse(0D);
        }
    }
}
