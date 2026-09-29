package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.client.RerankerClient;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.DocumentChunk;
import com.yizhaoqi.smartpai.repository.DocumentChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 混合搜索服务，结合文本匹配和向量相似度搜索
 * 支持权限过滤，确保用户只能搜索其有权限访问的文档
 */
@Service
public class HybridSearchService {

    private static final Logger logger = LoggerFactory.getLogger(HybridSearchService.class);

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private EmbeddingClient embeddingClient;

    /** 未启用 rerank 时没有对应 Bean，检索自动维持原有 RRF 行为。 */
    @Autowired(required = false)
    private RerankerClient rerankerClient;

    @Value("${rerank.api.candidate-limit:30}")
    private int rerankCandidateLimit;

    /** 每一路粗召回固定保留的候选数量。 */
    @Value("${rag.retrieval.candidate-limit:100}")
    private int retrievalCandidateLimit;

    /** SciFact 评测中表现最佳的 RRF 权重：关键词 10%，向量 90%。 */
    @Value("${rag.retrieval.rrf.keyword-weight:0.1}")
    private double rrfKeywordWeight;

    @Value("${rag.retrieval.rrf.vector-weight:0.9}")
    private double rrfVectorWeight;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgTagCacheService orgTagCacheService;

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private DocumentChunkRepository documentChunkRepository;

    /**
     * 使用文本匹配和向量相似度进行混合搜索，支持权限过滤
     * 该方法确保用户只能搜索其有权限访问的文档（自己的文档、公开文档、所属组织的文档）
     *
     * @param query  查询字符串
     * @param userId 用户ID
     * @param topK   返回结果数量
     * @return 搜索结果列表
     */
    public List<SearchResult> searchWithPermission(String query, String userId, int topK) {
        logger.debug("开始带权限搜索，查询: {}, 用户ID: {}", query, userId);
        
        try {
            // 获取用户有效的组织标签（包含层级关系）
            List<String> userEffectiveTags = getUserEffectiveOrgTags(userId);
            logger.debug("用户 {} 的有效组织标签: {}", userId, userEffectiveTags);

            // 获取用户的数据库ID用于权限过滤
            String userDbId = getUserDbId(userId);
            logger.debug("用户 {} 的数据库ID: {}", userId, userDbId);

            // 生成查询向量
            final List<Float> queryVector = embedToVectorList(query);

            // 如果向量生成失败，仅使用文本匹配
            if (queryVector == null) {
                logger.warn("向量生成失败，仅使用文本匹配进行搜索");
                return textOnlySearchWithPermission(query, userDbId, userEffectiveTags, topK);
            }

            int recallK = recallWindow(topK);
            Query permissionFilter = buildPermissionFilter(userDbId, userEffectiveTags);
            SearchResponse<EsDocument> keywordResponse = esClient.search(s -> s
                            .index(ElasticsearchService.INDEX_NAME)
                            .size(recallK)
                            .query(q -> q.bool(b -> b
                                    .must(must -> must.match(m -> m.field("textContent").query(query)))
                                    .filter(permissionFilter))),
                    EsDocument.class);
            SearchResponse<EsDocument> vectorResponse = esClient.search(s -> s
                            .index(ElasticsearchService.INDEX_NAME)
                            .size(recallK)
                            .knn(knn -> knn.field("vector")
                                    .queryVector(queryVector)
                                    .k(recallK)
                                    .numCandidates(recallK)
                                    .filter(permissionFilter)),
                    EsDocument.class);

            List<SearchResult> results = fuseResults(keywordResponse, vectorResponse, fusionLimit(topK));
            results = rerankIfAvailable(query, results, topK);

            logger.debug("返回搜索结果数量: {}", results.size());
            enrichResults(results, topK);
            return results;
        } catch (Exception e) {
            logger.error("带权限的搜索失败", e);
            // 发生异常时尝试使用纯文本搜索作为后备方案
            try {
                logger.info("尝试使用纯文本搜索作为后备方案");
                return textOnlySearchWithPermission(query, getUserDbId(userId), getUserEffectiveOrgTags(userId), topK);
            } catch (Exception fallbackError) {
                logger.error("后备搜索也失败", fallbackError);
                return Collections.emptyList();
            }
        }
    }

    /**
     * 仅使用文本匹配的带权限搜索方法
     */
    private List<SearchResult> textOnlySearchWithPermission(String query, String userDbId, List<String> userEffectiveTags, int topK) {
        try {
            logger.debug("开始执行纯文本搜索，用户数据库ID: {}, 标签: {}", userDbId, userEffectiveTags);

            SearchResponse<EsDocument> response = esClient.search(s -> s
                    .index(ElasticsearchService.INDEX_NAME)
                    .query(q -> q
                            .bool(b -> b
                                    // 匹配内容相关性
                                    .must(m -> m
                                            .match(ma -> ma
                                                    .field("textContent")
                                                    .query(query)
                                            )
                                    )
                                    // 权限过滤
                                    .filter(f -> f
                                            .bool(bf -> bf
                                                    // 条件1: 用户可以访问自己的文档
                                                    .should(s1 -> s1
                                                            .term(t -> t
                                                                    .field("userId")
                                                                    .value(userDbId)
                                                            )
                                                    )
                                                    // 条件2: 用户可以访问公开的文档
                                                    .should(s2 -> s2
                                                            .term(t -> t
                                                                    .field("isPublic")
                                                                    .value(true)
                                                            )
                                                    )
                                                    // 条件3: 用户可以访问其所属组织的文档（包含层级关系）
                                                    .should(s3 -> {
                                                        if (userEffectiveTags.isEmpty()) {
                                                            return s3.matchNone(mn -> mn);
                                                        } else if (userEffectiveTags.size() == 1) {
                                                            // 单个标签使用 term 查询
                                                            return s3.term(t -> t
                                                                    .field("orgTag")
                                                                    .value(userEffectiveTags.get(0))
                                                            );
                                                        } else {
                                                            // 多个标签使用 bool should 组合多个 term 查询
                                                            return s3.bool(innerBool -> {
                                                                userEffectiveTags.forEach(tag ->
                                                                        innerBool.should(sh -> sh.term(t -> t
                                                                                .field("orgTag")
                                                                                .value(tag)
                                                                        ))
                                                                );
                                                                return innerBool;
                                                            });
                                                        }
                                                    })
                                            )
                                    )
                            )
                    )
                    .minScore(0.3d)
                    .size(topK),
                    EsDocument.class
            );

            logger.debug("纯文本查询执行完成，命中数量: {}, 最大分数: {}", 
                response.hits().total().value(), response.hits().maxScore());

            List<SearchResult> results = response.hits().hits().stream()
                    .map(hit -> {
                        assert hit.source() != null;
                        logger.debug("纯文本搜索结果 - 文件: {}, 块: {}, 分数: {}, 内容: {}", 
                            hit.source().getFileMd5(), hit.source().getChunkId(), hit.score(), 
                            hit.source().getTextContent().substring(0, Math.min(50, hit.source().getTextContent().length())));
                        return toSearchResult(hit.source(), hit.score());
                    })
                    .collect(Collectors.toCollection(ArrayList::new));

            logger.debug("返回纯文本搜索结果数量: {}", results.size());
            enrichResults(results, topK);
            return results;
        } catch (Exception e) {
            logger.error("纯文本搜索失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 原始搜索方法，不包含权限过滤，保留向后兼容性
     */
    public List<SearchResult> search(String query, int topK) {
        try {
            logger.debug("开始混合检索，查询: {}, topK: {}", query, topK);
            logger.warn("使用了没有权限过滤的搜索方法，建议使用 searchWithPermission 方法");

            // 生成查询向量
            final List<Float> queryVector = embedToVectorList(query);
            
            // 如果向量生成失败，仅使用文本匹配
            if (queryVector == null) {
                logger.warn("向量生成失败，仅使用文本匹配进行搜索");
                return textOnlySearch(query, topK);
            }

            int recallK = recallWindow(topK);
            SearchResponse<EsDocument> keywordResponse = esClient.search(s -> s
                            .index(ElasticsearchService.INDEX_NAME)
                            .size(recallK)
                            .query(q -> q.match(m -> m.field("textContent").query(query))),
                    EsDocument.class);
            SearchResponse<EsDocument> vectorResponse = esClient.search(s -> s
                            .index(ElasticsearchService.INDEX_NAME)
                            .size(recallK)
                            .knn(knn -> knn.field("vector")
                                    .queryVector(queryVector)
                                    .k(recallK)
                                    .numCandidates(recallK)),
                    EsDocument.class);

            List<SearchResult> results = rerankIfAvailable(query,
                    fuseResults(keywordResponse, vectorResponse, fusionLimit(topK)), topK);
            enrichResults(results, topK);
            return results;
        } catch (Exception e) {
            logger.error("搜索失败", e);
            // 发生异常时尝试使用纯文本搜索作为后备方案
            try {
                logger.info("尝试使用纯文本搜索作为后备方案");
                return textOnlySearch(query, topK);
            } catch (Exception fallbackError) {
                logger.error("后备搜索也失败", fallbackError);
                throw new RuntimeException("搜索完全失败", fallbackError);
            }
        }
    }

    /**
     * 仅使用文本匹配的搜索方法
     */
    private List<SearchResult> textOnlySearch(String query, int topK) throws Exception {
        SearchResponse<EsDocument> response = esClient.search(s -> s
                .index(ElasticsearchService.INDEX_NAME)
                .query(q -> q
                        .match(m -> m
                                .field("textContent")
                                .query(query)
                        )
                )
                .size(topK),
                EsDocument.class
        );

        List<SearchResult> results = response.hits().hits().stream()
                .map(hit -> {
                    assert hit.source() != null;
                    return toSearchResult(hit.source(), hit.score());
                })
                .collect(Collectors.toCollection(ArrayList::new));
        enrichResults(results, topK);
        return results;
    }

    /**
     * 每一路固定 Top-100 粗召回。若调用方未来要求的最终数量更大，则至少满足调用方数量。
     */
    private int recallWindow(int topK) {
        return Math.max(topK, retrievalCandidateLimit);
    }

    /** 构建关键词检索和向量检索共用的权限过滤条件。 */
    private Query buildPermissionFilter(String userDbId, List<String> userEffectiveTags) {
        return Query.of(query -> query.bool(permission -> {
            permission.should(own -> own.term(term -> term.field("userId").value(userDbId)));
            permission.should(publicDocument -> publicDocument.term(term -> term.field("isPublic").value(true)));
            if (userEffectiveTags.isEmpty()) {
                permission.should(noOrganization -> noOrganization.matchNone(matchNone -> matchNone));
            } else {
                userEffectiveTags.forEach(tag -> permission.should(organization -> organization
                        .term(term -> term.field("orgTag").value(tag))));
            }
            // 以上 should 条件是“自己的文档、公开文档、所属组织文档”的并集。
            permission.minimumShouldMatch("1");
            return permission;
        }));
    }

    /** 将两路 Elasticsearch 命中按稳定的 ES _id 做应用层 RRF 融合。 */
    private List<SearchResult> fuseResults(SearchResponse<EsDocument> keywordResponse,
                                           SearchResponse<EsDocument> vectorResponse,
                                           int topK) {
        List<Hit<EsDocument>> keywordHits = validHits(keywordResponse);
        List<Hit<EsDocument>> vectorHits = validHits(vectorResponse);
        List<SearchResult> results = ReciprocalRankFusion.fuse(keywordHits, vectorHits, Hit::id,
                        rrfKeywordWeight, rrfVectorWeight, topK).stream()
                .map(scoredHit -> toSearchResult(scoredHit.item().source(), scoredHit.score()))
                .collect(Collectors.toCollection(ArrayList::new));
        logger.debug("应用层 RRF 融合完成：关键词候选={}, 向量候选={}, 权重={}/{}, 返回={}",
                keywordHits.size(), vectorHits.size(), rrfKeywordWeight, rrfVectorWeight, results.size());
        return results;
    }

    private List<Hit<EsDocument>> validHits(SearchResponse<EsDocument> response) {
        return response.hits().hits().stream()
                .filter(hit -> hit.id() != null && hit.source() != null)
                .toList();
    }

    /** 启用 rerank 时保留更多融合候选；未启用时返回原有最终数量。 */
    private int fusionLimit(int topK) {
        if (rerankerClient == null) {
            return topK;
        }
        return Math.max(topK, rerankCandidateLimit);
    }

    /**
     * 将 RRF 候选交给 cross-encoder 精排。精排异常时降级回 RRF 排序，保证检索可用。
     */
    private List<SearchResult> rerankIfAvailable(String query, List<SearchResult> candidates, int topK) {
        if (rerankerClient == null || candidates.size() <= topK) {
            return new ArrayList<>(candidates.subList(0, Math.min(topK, candidates.size())));
        }
        try {
            List<String> documents = candidates.stream().map(this::rerankText).toList();
            List<RerankerClient.RankedDocument> ranked = rerankerClient.rerank(query, documents, topK);
            List<SearchResult> results = new ArrayList<>(ranked.size());
            for (RerankerClient.RankedDocument rank : ranked) {
                if (rank.index() < 0 || rank.index() >= candidates.size()) {
                    throw new IllegalStateException("重排序返回了非法候选下标: " + rank.index());
                }
                SearchResult result = candidates.get(rank.index());
                result.setScore(rank.relevanceScore());
                results.add(result);
            }
            logger.debug("重排序完成：候选={}, 返回={}", candidates.size(), results.size());
            return results;
        } catch (Exception exception) {
            logger.warn("重排序失败，降级使用 RRF 排序：{}", exception.getMessage());
            return new ArrayList<>(candidates.subList(0, Math.min(topK, candidates.size())));
        }
    }

    /** 标题路径是检索语义的一部分，因此和正文一起送入 reranker。 */
    private String rerankText(SearchResult result) {
        if (result.getTitlePath() == null || result.getTitlePath().isBlank()) {
            return result.getTextContent();
        }
        return result.getTitlePath() + "\n" + result.getTextContent();
    }

    /**
     * 生成查询向量，返回 List<Float>，失败时返回 null
     */
    private List<Float> embedToVectorList(String text) {
        try {
            List<float[]> vecs = embeddingClient.embed(List.of(text));
            if (vecs == null || vecs.isEmpty()) {
                logger.warn("生成的向量为空");
                return null;
            }
            float[] raw = vecs.get(0);
            List<Float> list = new ArrayList<>(raw.length);
            for (float v : raw) {
                list.add(v);
            }
            return list;
        } catch (Exception e) {
            logger.error("生成向量失败", e);
            return null;
        }
    }
    
    /**
     * 获取用户的有效组织标签（包含层级关系）
     */
    private List<String> getUserEffectiveOrgTags(String userId) {
        logger.debug("获取用户有效组织标签，用户ID: {}", userId);
        try {
            // 获取用户名
            User user;
            try {
                Long userIdLong = Long.parseLong(userId);
                logger.debug("解析用户ID为Long: {}", userIdLong);
                user = userRepository.findById(userIdLong)
                    .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过ID找到用户: {}", user.getUsername());
            } catch (NumberFormatException e) {
                // 如果userId不是数字格式，则假设它就是username
                logger.debug("用户ID不是数字格式，作为用户名查找: {}", userId);
                user = userRepository.findByUsername(userId)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过用户名找到用户: {}", user.getUsername());
            }
            
            // 通过orgTagCacheService获取用户的有效标签集合
            List<String> effectiveTags = orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
            logger.debug("用户 {} 的有效组织标签: {}", user.getUsername(), effectiveTags);
            return effectiveTags;
        } catch (Exception e) {
            logger.error("获取用户有效组织标签失败: {}", e.getMessage(), e);
            return Collections.emptyList(); // 返回空列表作为默认值
        }
    }

    /**
     * 获取用户的数据库ID用于权限过滤
     */
    private String getUserDbId(String userId) {
        logger.debug("获取用户数据库ID，用户ID: {}", userId);
        try {
            // 获取用户名
            User user;
            try {
                Long userIdLong = Long.parseLong(userId);
                logger.debug("解析用户ID为Long: {}", userIdLong);
                user = userRepository.findById(userIdLong)
                    .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过ID找到用户: {}", user.getUsername());
                return userIdLong.toString(); // 如果输入已经是数字ID，直接返回
            } catch (NumberFormatException e) {
                // 如果userId不是数字格式，则假设它就是username
                logger.debug("用户ID不是数字格式，作为用户名查找: {}", userId);
                user = userRepository.findByUsername(userId)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过用户名找到用户: {}, ID: {}", user.getUsername(), user.getId());
                return user.getId().toString(); // 返回用户的数据库ID
            }
        } catch (Exception e) {
            logger.error("获取用户数据库ID失败: {}", e.getMessage(), e);
            throw new RuntimeException("获取用户数据库ID失败", e);
        }
    }

    private void attachFileNames(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        try {
            // 收集所有唯一的 fileMd5
            Set<String> md5Set = results.stream()
                    .map(SearchResult::getFileMd5)
                    .collect(Collectors.toSet());
            List<FileUpload> uploads = fileUploadRepository.findByFileMd5In(new java.util.ArrayList<>(md5Set));
            Map<String, String> md5ToName = uploads.stream()
                    .collect(Collectors.toMap(FileUpload::getFileMd5, FileUpload::getFileName));
            // 填充文件名
            results.forEach(r -> {
                if (r.getFileName() == null) {
                    r.setFileName(md5ToName.get(r.getFileMd5()));
                }
            });
        } catch (Exception e) {
            logger.error("补充文件名失败", e);
        }
    }

    private SearchResult toSearchResult(EsDocument document, Double score) {
        SearchResult result = new SearchResult(document.getFileMd5(), document.getChunkId(),
                document.getTextContent(), score, document.getUserId(), document.getOrgTag(),
                document.isPublic());
        result.setFileUploadId(document.getFileUploadId());
        result.setSectionId(document.getSectionId());
        result.setTitlePath(document.getTitlePath());
        result.setPageStart(document.getPageStart());
        result.setPageEnd(document.getPageEnd());
        result.setEstimatedTokenCount(document.getEstimatedTokenCount());
        result.setFileName(document.getFileName());
        return result;
    }

    /** 为前五个主命中补齐同章节相邻块，整体不超过 3500 估算 token。 */
    private void enrichResults(List<SearchResult> results, int topK) {
        attachFileNames(results);
        results.forEach(this::fillSourceLabel);
        if (results.isEmpty()) {
            return;
        }
        List<SearchResult> primary = new ArrayList<>(results.subList(0, Math.min(5, results.size())));
        List<SearchResult> additional = new ArrayList<>();
        int budget = primary.stream().map(SearchResult::getEstimatedTokenCount)
                .mapToInt(value -> value == null ? 0 : value).sum();
        for (SearchResult hit : primary) {
            if (hit.getSectionId() == null || hit.getChunkId() == null || hit.getFileUploadId() == null) {
                continue;
            }
            List<DocumentChunk> neighbors = documentChunkRepository.findBySectionIdAndOrdinalBetweenOrderByOrdinalAsc(
                    hit.getSectionId(), Math.max(1, hit.getChunkId() - 1), hit.getChunkId() + 1);
            for (DocumentChunk neighbor : neighbors) {
                if (neighbor.getOrdinal().equals(hit.getChunkId()) || containsChunk(results, neighbor)
                        || containsChunk(additional, neighbor)) {
                    continue;
                }
                int cost = neighbor.getEstimatedTokenCount();
                if (budget + cost > 3500) {
                    continue;
                }
                budget += cost;
                SearchResult context = new SearchResult(neighbor.getFileUploadId().equals(hit.getFileUploadId())
                        ? hit.getFileMd5() : null, neighbor.getOrdinal(), neighbor.getTextContent(), 0.0,
                        hit.getUserId(), hit.getOrgTag(), Boolean.TRUE.equals(hit.getIsPublic()), hit.getFileName());
                context.setFileUploadId(neighbor.getFileUploadId());
                context.setSectionId(neighbor.getSectionId());
                context.setTitlePath(neighbor.getTitlePath());
                context.setPageStart(neighbor.getPageStart());
                context.setPageEnd(neighbor.getPageEnd());
                context.setEstimatedTokenCount(neighbor.getEstimatedTokenCount());
                fillSourceLabel(context);
                additional.add(context);
            }
        }
        results.addAll(additional);
    }

    private boolean containsChunk(List<SearchResult> results, DocumentChunk chunk) {
        return results.stream().anyMatch(result -> chunk.getFileUploadId().equals(result.getFileUploadId())
                && chunk.getOrdinal().equals(result.getChunkId()));
    }

    private void fillSourceLabel(SearchResult result) {
        result.setSourceLabel(SourceLabelFormatter.format(result.getFileName(), result.getTitlePath(),
                result.getPageStart(), result.getPageEnd(), result.getTextContent()));
    }
}
