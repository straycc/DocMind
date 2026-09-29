package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.model.DocumentChunk;
import com.yizhaoqi.smartpai.model.DocumentProcessingStatus;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.DocumentChunkRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.IntStream;

/** 从 MySQL 子块事实记录生成 Elasticsearch 全文和向量检索投影。 */
@Service
public class VectorizationService {
    private static final Logger logger = LoggerFactory.getLogger(VectorizationService.class);

    private final EmbeddingClient embeddingClient;
    private final ElasticsearchService elasticsearchService;
    private final DocumentChunkRepository chunkRepository;
    private final FileUploadRepository fileUploadRepository;

    public VectorizationService(EmbeddingClient embeddingClient, ElasticsearchService elasticsearchService,
                                DocumentChunkRepository chunkRepository, FileUploadRepository fileUploadRepository) {
        this.embeddingClient = embeddingClient;
        this.elasticsearchService = elasticsearchService;
        this.chunkRepository = chunkRepository;
        this.fileUploadRepository = fileUploadRepository;
    }

    @Transactional(noRollbackFor = IllegalStateException.class)
    public void vectorize(Long fileUploadId) {
        FileUpload upload = fileUploadRepository.findById(fileUploadId)
                .orElseThrow(() -> new IllegalArgumentException("文件上传记录不存在: " + fileUploadId));
        List<DocumentChunk> chunks = chunkRepository.findByFileUploadIdOrderByOrdinalAsc(fileUploadId);
        if (chunks.isEmpty()) {
            upload.setProcessingStatus(DocumentProcessingStatus.FAILED);
            upload.setProcessingError("没有可向量化的文档子块");
            fileUploadRepository.save(upload);
            throw new IllegalStateException("没有可向量化的文档子块");
        }
        try {
            upload.setProcessingStatus(DocumentProcessingStatus.EMBEDDING);
            upload.setProcessingError(null);
            fileUploadRepository.save(upload);
            List<float[]> vectors = embeddingClient.embed(chunks.stream()
                    .map(chunk -> DocumentEmbeddingTextBuilder.build(upload.getFileName(), chunk.getTitlePath(),
                            chunk.getTextContent()))
                    .toList());
            validateVectors(vectors, chunks.size());

            // 先清除旧投影，bulk 失败时保留 MySQL 子块以便仅重试向量化。
            elasticsearchService.deleteByFileUploadId(fileUploadId);
            List<EsDocument> documents = IntStream.range(0, chunks.size()).mapToObj(index -> {
                DocumentChunk chunk = chunks.get(index);
                return new EsDocument(fileUploadId + ":" + chunk.getChunkerVersion() + ":" + chunk.getOrdinal(),
                        fileUploadId, upload.getFileMd5(), upload.getFileName(), chunk.getOrdinal(), chunk.getSectionId(), chunk.getTextContent(),
                        chunk.getTitlePath(), chunk.getPageStart(), chunk.getPageEnd(), chunk.getSourceLocator(),
                        chunk.getEstimatedTokenCount(), vectors.get(index), embeddingClient.getModelId(), upload.getUserId(),
                        upload.getOrgTag(), upload.isPublic());
            }).toList();
            elasticsearchService.bulkIndex(documents);
            upload.setEmbeddingVersion(embeddingClient.getModelId());
            upload.setProcessingStatus(DocumentProcessingStatus.READY);
            upload.setProcessingError(null);
            fileUploadRepository.save(upload);
            logger.info("文件向量化完成: fileUploadId={}, chunks={}", fileUploadId, chunks.size());
        } catch (Exception exception) {
            upload.setProcessingStatus(DocumentProcessingStatus.FAILED);
            upload.setProcessingError(limitError(exception));
            fileUploadRepository.save(upload);
            throw new IllegalStateException("文档向量化失败: " + fileUploadId, exception);
        }
    }

    /** 向后兼容旧入口；权限信息以 file_upload 中的事实字段为准。 */
    public void vectorize(String fileMd5, String userId, String orgTag, boolean isPublic) {
        FileUpload upload = fileUploadRepository.findByFileMd5(fileMd5)
                .orElseThrow(() -> new IllegalArgumentException("文件上传记录不存在: " + fileMd5));
        vectorize(upload.getId());
    }

    private void validateVectors(List<float[]> vectors, int expectedCount) {
        if (vectors == null || vectors.size() != expectedCount) {
            throw new IllegalStateException("Embedding 返回数量不匹配，期望=" + expectedCount
                    + "，实际=" + (vectors == null ? 0 : vectors.size()));
        }
        for (float[] vector : vectors) {
            if (vector == null || vector.length != embeddingClient.getDimension()) {
                throw new IllegalStateException("Embedding 维度不匹配，期望=" + embeddingClient.getDimension()
                        + "，实际=" + (vector == null ? 0 : vector.length));
            }
        }
    }

    private String limitError(Exception exception) {
        String text = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        return text.length() > 4000 ? text.substring(0, 4000) : text;
    }
}
