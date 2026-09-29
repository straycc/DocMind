package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.yizhaoqi.smartpai.entity.EsDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

// Elasticsearch操作封装服务
@Service
public class ElasticsearchService {
    public static final String INDEX_NAME = "knowledge_base_v1";
    private static final int BULK_BATCH_SIZE = 200;

    private static final Logger logger = LoggerFactory.getLogger(ElasticsearchService.class);

    @Autowired
    private ElasticsearchClient esClient;

    /**
     * 批量索引文档到Elasticsearch中
     * 通过接收一个EsDocument对象列表，将这些文档批量索引到名为"knowledge_base"的索引中
     * 使用Elasticsearch的Bulk API来执行批量索引操作，以提高索引效率
     *
     * @param documents 文档列表，每个文档都将被索引到Elasticsearch中
     */
    public void bulkIndex(List<EsDocument> documents) {
        bulkIndex(INDEX_NAME, documents);
    }

    /**
     * 将文档写入指定索引。评测数据使用独立索引，避免与正式知识库混在一起。
     */
    public void bulkIndex(String indexName, List<EsDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        try {
            logger.info("开始批量索引文档到 Elasticsearch，索引: {}, 文档数量: {}", indexName, documents.size());
            for (int start = 0; start < documents.size(); start += BULK_BATCH_SIZE) {
                int end = Math.min(start + BULK_BATCH_SIZE, documents.size());
                bulkIndexBatch(indexName, documents.subList(start, end));
                logger.info("Elasticsearch 批量索引进度：{}/{}", end, documents.size());
            }
            logger.info("批量索引成功完成，索引: {}, 文档数量: {}", indexName, documents.size());
        } catch (Exception e) {
            logger.error("批量索引失败，文档数量: {}", documents.size(), e);
            // 如果发生异常，抛出运行时异常，表明批量索引失败
            throw new RuntimeException("批量索引失败", e);
        }
    }

    /**
     * 根据file_md5删除文档
     * @param fileMd5 文件指纹
     */
    public void deleteByFileMd5(String fileMd5) {
        try {
            DeleteByQueryRequest request = DeleteByQueryRequest.of(d -> d
                    .index(INDEX_NAME)
                    // 删除接口允许重复调用；其他请求已删掉同一投影时，跳过版本冲突。
                    .conflicts(Conflicts.Proceed)
                    .query(q -> q.term(t -> t.field("fileMd5").value(fileMd5)))
            );
            esClient.deleteByQuery(request);
        } catch (Exception e) {
            throw new RuntimeException("删除文档失败", e);
        }
    }

    /** 删除指定文件当前版本的 ES 投影，供至少一次消费重试使用。 */
    public void deleteByFileUploadId(Long fileUploadId) {
        try {
            DeleteByQueryRequest request = DeleteByQueryRequest.of(d -> d
                    .index(INDEX_NAME)
                    // Kafka 重试或用户重复点击删除时，ES 子文档可能已被并发删除。
                    .conflicts(Conflicts.Proceed)
                    .query(q -> q.term(t -> t.field("fileUploadId").value(fileUploadId))));
            esClient.deleteByQuery(request);
        } catch (Exception e) {
            throw new RuntimeException("删除文件 ES 投影失败", e);
        }
    }

    /** 单批写入，控制请求体大小，避免大规模向量导入触发 ES HTTP 限制。 */
    private void bulkIndexBatch(String indexName, List<EsDocument> documents) throws Exception {
        List<BulkOperation> bulkOperations = documents.stream()
                .map(doc -> BulkOperation.of(op -> op.index(idx -> idx
                        .index(indexName)
                        .id(doc.getId())
                        .document(doc))))
                .toList();
        BulkRequest request = BulkRequest.of(builder -> builder.operations(bulkOperations));
        BulkResponse response = esClient.bulk(request);
        if (!response.errors()) {
            return;
        }
        for (BulkResponseItem item : response.items()) {
            if (item.error() != null) {
                logger.error("文档索引失败 - ID: {}, 错误: {}", item.id(), item.error().reason());
            }
        }
        throw new RuntimeException("批量索引部分失败，请检查日志");
    }
}
