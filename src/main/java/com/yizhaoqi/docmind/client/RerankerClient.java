package com.yizhaoqi.docmind.client;

import java.util.List;

/** 查询与候选文本的相关性重排序接口。 */
public interface RerankerClient {

    /**
     * 对候选文本重排序。
     *
     * @param query 用户问题
     * @param documents 候选文本，返回结果中的 index 对应此列表的原始下标
     * @param topN 需要保留的最高相关候选数
     */
    List<RankedDocument> rerank(String query, List<String> documents, int topN);

    record RankedDocument(int index, double relevanceScore) {
    }
}
