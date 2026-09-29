package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.elasticsearch.indices.DeleteIndexRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.entity.DocumentElement;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.model.DocumentChunk;
import com.yizhaoqi.smartpai.model.DocumentElementType;
import com.yizhaoqi.smartpai.model.DocumentProcessingStatus;
import com.yizhaoqi.smartpai.model.DocumentSection;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.DocumentChunkRepository;
import com.yizhaoqi.smartpai.repository.DocumentSectionRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SciFact 核心 RAG 评测语料导入器。
 *
 * <p>该任务只在显式打开配置后执行。默认导入证据论文与固定数量的干扰论文；打开
 * full-corpus 后导入 corpus.jsonl 的全部论文。评测问题和标准证据写入本地清单，不新增 MySQL 评测表。</p>
 */
@Component
@ConditionalOnProperty(prefix = "rag-eval.scifact", name = "import-enabled", havingValue = "true")
public class SciFactEvaluationImporter implements CommandLineRunner {
    private static final Logger logger = LoggerFactory.getLogger(SciFactEvaluationImporter.class);
    private static final String EVAL_USER_ID = "rag-eval";
    private static final String EVAL_ORG_TAG = "rag-eval";
    private static final String PARSER_VERSION = "scifact-jsonl-v1";
    private static final String CHUNKER_VERSION = "rag-eval-v1";

    private final ObjectMapper objectMapper;
    private final FileUploadRepository fileUploadRepository;
    private final DocumentSectionRepository sectionRepository;
    private final DocumentChunkRepository chunkRepository;
    private final EmbeddingClient embeddingClient;
    private final ElasticsearchService elasticsearchService;
    private final ElasticsearchClient esClient;
    private final String dataDirectory;
    private final int claimLimit;
    private final int distractorLimit;
    private final boolean fullCorpus;
    private final int minimumCorpusDocuments;
    private final String indexName;
    private final boolean resetIndex;
    private final Path reportFile;
    private final Resource mappingResource;

    public SciFactEvaluationImporter(
            ObjectMapper objectMapper,
            FileUploadRepository fileUploadRepository,
            DocumentSectionRepository sectionRepository,
            DocumentChunkRepository chunkRepository,
            EmbeddingClient embeddingClient,
            ElasticsearchService elasticsearchService,
            ElasticsearchClient esClient,
            @Value("${rag-eval.scifact.data-dir:D:/Work/java/code/scifact/data}") String dataDirectory,
            @Value("${rag-eval.scifact.claim-limit:188}") int claimLimit,
            @Value("${rag-eval.scifact.distractor-limit:200}") int distractorLimit,
            @Value("${rag-eval.scifact.full-corpus:false}") boolean fullCorpus,
            @Value("${rag-eval.scifact.minimum-corpus-documents:5183}") int minimumCorpusDocuments,
            @Value("${rag-eval.scifact.index:rag_eval_scifact_dev_v1}") String indexName,
            @Value("${rag-eval.scifact.reset-index:false}") boolean resetIndex,
            @Value("${rag-eval.scifact.report-file:rag-eval-results/scifact-dev-manifest.json}") String reportFile,
            @Value("classpath:es-mappings/knowledge_base.json") Resource mappingResource) {
        this.objectMapper = objectMapper;
        this.fileUploadRepository = fileUploadRepository;
        this.sectionRepository = sectionRepository;
        this.chunkRepository = chunkRepository;
        this.embeddingClient = embeddingClient;
        this.elasticsearchService = elasticsearchService;
        this.esClient = esClient;
        this.dataDirectory = dataDirectory;
        this.claimLimit = claimLimit;
        this.distractorLimit = distractorLimit;
        this.fullCorpus = fullCorpus;
        this.minimumCorpusDocuments = minimumCorpusDocuments;
        this.indexName = indexName;
        this.resetIndex = resetIndex;
        this.reportFile = Path.of(reportFile);
        this.mappingResource = mappingResource;
    }

    @Override
    public void run(String... args) throws Exception {
        validateConfiguration();
        Path basePath = Path.of(dataDirectory);
        List<EvaluationClaim> claims = readClaims(basePath.resolve("claims_dev.jsonl"));
        Map<String, SciFactDocument> corpus = readCorpus(basePath.resolve("corpus.jsonl"));
        List<SciFactDocument> selectedDocuments = selectDocuments(claims, corpus);

        logger.info("开始导入 SciFact 评测集：scope={}, claims={}, documents={}, corpusRecords={}, index={}",
                fullCorpus ? "full" : "selected", claims.size(), selectedDocuments.size(), corpus.size(), indexName);
        ensureIndex();

        List<PreparedDocument> preparedDocuments = new ArrayList<>();
        try {
            for (int documentIndex = 0; documentIndex < selectedDocuments.size(); documentIndex++) {
                SciFactDocument document = selectedDocuments.get(documentIndex);
                preparedDocuments.add(prepareDocument(document));
                if ((documentIndex + 1) % 250 == 0 || documentIndex + 1 == selectedDocuments.size()) {
                    logger.info("SciFact 文档事实数据已准备：{}/{}", documentIndex + 1, selectedDocuments.size());
                }
            }
            List<DocumentChunk> chunks = preparedDocuments.stream()
                    .flatMap(document -> document.chunks().stream())
                    .toList();
            Map<Long, FileUpload> uploadsById = new LinkedHashMap<>();
            for (PreparedDocument preparedDocument : preparedDocuments) {
                uploadsById.put(preparedDocument.upload().getId(), preparedDocument.upload());
            }
            List<float[]> vectors = embeddingClient.embed(chunks.stream()
                    .map(chunk -> embeddingText(chunk, uploadsById.get(chunk.getFileUploadId())))
                    .toList());
            validateVectors(vectors, chunks.size());

            List<EsDocument> esDocuments = new ArrayList<>(chunks.size());
            for (int index = 0; index < chunks.size(); index++) {
                DocumentChunk chunk = chunks.get(index);
                FileUpload upload = uploadsById.get(chunk.getFileUploadId());
                if (upload == null) {
                    throw new IllegalStateException("找不到子块所属的评测文件: " + chunk.getFileUploadId());
                }
                esDocuments.add(new EsDocument(chunk.getSourceLocator() + ":" + CHUNKER_VERSION + ":" + chunk.getOrdinal(),
                        upload.getId(), upload.getFileMd5(), upload.getFileName(), chunk.getOrdinal(), chunk.getSectionId(),
                        chunk.getTextContent(), chunk.getTitlePath(), chunk.getPageStart(), chunk.getPageEnd(),
                        chunk.getSourceLocator(), chunk.getEstimatedTokenCount(), vectors.get(index), embeddingClient.getModelId(),
                        EVAL_USER_ID, EVAL_ORG_TAG, false));
            }
            elasticsearchService.bulkIndex(indexName, esDocuments);

            for (PreparedDocument preparedDocument : preparedDocuments) {
                FileUpload upload = preparedDocument.upload();
                upload.setEmbeddingVersion(embeddingClient.getModelId());
                upload.setProcessingStatus(DocumentProcessingStatus.READY);
                upload.setProcessingError(null);
                fileUploadRepository.save(upload);
            }
            writeManifest(claims, selectedDocuments.size(), corpus.size());
            logger.info("SciFact 评测语料导入完成：scope={}, claims={}, documents={}, chunks={}, index={}",
                    fullCorpus ? "full" : "selected", claims.size(), selectedDocuments.size(), chunks.size(), indexName);
        } catch (Exception exception) {
            for (PreparedDocument preparedDocument : preparedDocuments) {
                FileUpload upload = preparedDocument.upload();
                upload.setProcessingStatus(DocumentProcessingStatus.FAILED);
                upload.setProcessingError(limitError(exception));
                fileUploadRepository.save(upload);
            }
            throw exception;
        }
    }

    private void validateConfiguration() {
        if (claimLimit < 1 || distractorLimit < 0 || minimumCorpusDocuments < 1) {
            throw new IllegalArgumentException("SciFact 导入数量配置不合法");
        }
        if (embeddingClient.getDimension() != 2048) {
            throw new IllegalStateException("评测索引映射当前固定为 2048 维，实际 Embedding 维度为 "
                    + embeddingClient.getDimension());
        }
    }

    private List<EvaluationClaim> readClaims(Path claimsFile) throws IOException {
        requireFile(claimsFile);
        List<EvaluationClaim> claims = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(claimsFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null && claims.size() < claimLimit) {
                JsonNode node = objectMapper.readTree(line);
                JsonNode evidence = node.path("evidence");
                if (!evidence.isObject() || evidence.isEmpty()) {
                    continue;
                }
                List<String> evidenceDocumentIds = new ArrayList<>();
                Iterator<String> ids = evidence.fieldNames();
                while (ids.hasNext()) {
                    evidenceDocumentIds.add(ids.next());
                }
                claims.add(new EvaluationClaim(node.path("id").asLong(), node.path("claim").asText(),
                        List.copyOf(evidenceDocumentIds)));
            }
        }
        if (claims.size() < claimLimit) {
            throw new IllegalStateException("有效 SciFact dev claim 不足，期望=" + claimLimit + "，实际=" + claims.size());
        }
        return claims;
    }

    private Map<String, SciFactDocument> readCorpus(Path corpusFile) throws IOException {
        requireFile(corpusFile);
        Map<String, SciFactDocument> corpus = new LinkedHashMap<>();
        int lineNumber = 0;
        int skippedRecords = 0;
        try (BufferedReader reader = Files.newBufferedReader(corpusFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonNode node = objectMapper.readTree(line);
                    String documentId = node.path("doc_id").asText();
                    List<String> abstractSentences = new ArrayList<>();
                    for (JsonNode sentence : node.path("abstract")) {
                        abstractSentences.add(sentence.asText());
                    }
                    corpus.put(documentId, new SciFactDocument(documentId, node.path("title").asText(), abstractSentences));
                } catch (JsonProcessingException exception) {
                    skippedRecords++;
                    logger.warn("跳过格式错误的 SciFact corpus 记录：line={}, reason={}",
                            lineNumber, exception.getOriginalMessage());
                }
            }
        }
        if (skippedRecords > 0) {
            logger.warn("SciFact corpus 读取完成：有效记录={}，跳过格式错误记录={}", corpus.size(), skippedRecords);
        }
        if (corpus.isEmpty()) {
            throw new IllegalStateException("SciFact corpus 中没有可用记录: " + corpusFile);
        }
        return corpus;
    }

    private List<SciFactDocument> selectDocuments(List<EvaluationClaim> claims, Map<String, SciFactDocument> corpus) {
        if (fullCorpus) {
            if (corpus.size() < minimumCorpusDocuments) {
                throw new IllegalStateException("SciFact 完整语料数量不足，期望至少=" + minimumCorpusDocuments
                        + "，实际=" + corpus.size() + "。请重新下载官方 corpus.jsonl 后再执行全量导入。");
            }
            for (EvaluationClaim claim : claims) {
                for (String evidenceDocumentId : claim.evidenceDocumentIds()) {
                    if (!corpus.containsKey(evidenceDocumentId)) {
                        throw new IllegalStateException("SciFact 完整语料缺少 claim 的证据文档: "
                                + evidenceDocumentId + "，claim=" + claim.id());
                    }
                }
            }
            if (distractorLimit > 0) {
                logger.info("full-corpus=true 时忽略 distractor-limit={}，将导入全部 {} 篇论文",
                        distractorLimit, corpus.size());
            }
            return new ArrayList<>(corpus.values());
        }
        Set<String> requiredDocumentIds = new LinkedHashSet<>();
        for (EvaluationClaim claim : claims) {
            requiredDocumentIds.addAll(claim.evidenceDocumentIds());
        }
        List<SciFactDocument> selected = new ArrayList<>();
        for (String documentId : requiredDocumentIds) {
            SciFactDocument document = corpus.get(documentId);
            if (document == null) {
                throw new IllegalStateException("SciFact corpus 缺少 claim 的证据文档: " + documentId);
            }
            selected.add(document);
        }
        for (SciFactDocument document : corpus.values()) {
            if (selected.size() >= requiredDocumentIds.size() + distractorLimit) {
                break;
            }
            if (!requiredDocumentIds.contains(document.documentId())) {
                selected.add(document);
            }
        }
        return selected;
    }

    private PreparedDocument prepareDocument(SciFactDocument document) {
        String content = document.content();
        String sourceLocator = "scifact:" + document.documentId();
        String fileMd5 = sha256("scifact:" + document.documentId()).substring(0, 32);
        FileUpload upload = fileUploadRepository.findByFileMd5AndUserId(fileMd5, EVAL_USER_ID)
                .orElseGet(FileUpload::new);
        if (upload.getId() != null) {
            chunkRepository.deleteByFileUploadId(upload.getId());
            sectionRepository.deleteByFileUploadId(upload.getId());
        }
        upload.setFileMd5(fileMd5);
        upload.setFileName(abbreviate("SciFact-" + document.documentId() + "-" + document.title(), 240));
        upload.setTotalSize(content.getBytes(StandardCharsets.UTF_8).length);
        upload.setStatus(1);
        upload.setObjectKey(null);
        upload.setContentHash(sha256(content));
        upload.setParserVersion(PARSER_VERSION);
        upload.setChunkerVersion(CHUNKER_VERSION);
        upload.setEmbeddingVersion(null);
        upload.setProcessingStatus(DocumentProcessingStatus.EMBEDDING);
        upload.setProcessingError(null);
        upload.setUserId(EVAL_USER_ID);
        upload.setOrgTag(EVAL_ORG_TAG);
        upload.setPublic(false);
        upload = fileUploadRepository.saveAndFlush(upload);

        String titlePath = document.title();
        DocumentSection section = new DocumentSection();
        section.setFileUploadId(upload.getId());
        section.setOrdinal(1);
        section.setTitlePath(titlePath);
        section = sectionRepository.saveAndFlush(section);

        List<DocumentChunk> chunks = new ArrayList<>();
        String embeddingPrefix = DocumentEmbeddingTextBuilder.buildPrefix(upload.getFileName(), titlePath);
        List<DocumentChunker.ChunkDraft> drafts = DocumentChunker.chunk(embeddingPrefix,
                List.of(new DocumentElement(DocumentElementType.NARRATIVE, content, null, sourceLocator, 0)));
        for (int ordinal = 1; ordinal <= drafts.size(); ordinal++) {
            DocumentChunker.ChunkDraft draft = drafts.get(ordinal - 1);
            DocumentChunk chunk = new DocumentChunk();
            chunk.setFileUploadId(upload.getId());
            chunk.setSectionId(section.getId());
            chunk.setOrdinal(ordinal);
            chunk.setTextContent(draft.text());
            chunk.setTitlePath(titlePath);
            chunk.setPageStart(draft.pageStart());
            chunk.setPageEnd(draft.pageEnd());
            chunk.setSourceLocator(draft.sourceLocator());
            String embeddingText = DocumentEmbeddingTextBuilder.build(upload.getFileName(), titlePath, draft.text());
            chunk.setEstimatedTokenCount(CjkTokenEstimator.estimate(embeddingText));
            chunk.setContentHash(sha256(draft.text()));
            chunk.setChunkerVersion(CHUNKER_VERSION);
            chunks.add(chunk);
        }
        return new PreparedDocument(upload, chunkRepository.saveAll(chunks));
    }

    private String embeddingText(DocumentChunk chunk, FileUpload upload) {
        if (upload == null) {
            throw new IllegalStateException("找不到子块所属的评测文件: " + chunk.getFileUploadId());
        }
        return DocumentEmbeddingTextBuilder.build(upload.getFileName(), chunk.getTitlePath(), chunk.getTextContent());
    }

    private void ensureIndex() throws IOException {
        boolean exists = esClient.indices().exists(ExistsRequest.of(request -> request.index(indexName))).value();
        if (resetIndex && exists) {
            esClient.indices().delete(DeleteIndexRequest.of(request -> request.index(indexName)));
            exists = false;
        }
        if (!exists) {
            String mappingJson;
            try (var stream = mappingResource.getInputStream()) {
                mappingJson = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            esClient.indices().create(CreateIndexRequest.of(request -> request
                    .index(indexName)
                    .withJson(new StringReader(mappingJson))));
        }
    }

    private void writeManifest(List<EvaluationClaim> claims, int documentCount, int corpusRecordCount) throws IOException {
        Path parent = reportFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("dataset", "SciFact dev");
        manifest.put("corpusScope", fullCorpus ? "full" : "selected");
        manifest.put("corpusRecordCount", corpusRecordCount);
        manifest.put("claimCount", claims.size());
        manifest.put("documentCount", documentCount);
        manifest.put("esIndex", indexName);
        manifest.put("claims", claims);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(reportFile.toFile(), manifest);
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

    private void requireFile(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("找不到 SciFact 数据文件: " + file);
        }
    }

    private String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte item : hash) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
    }

    private String abbreviate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private String limitError(Exception exception) {
        String text = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        return text.length() > 4000 ? text.substring(0, 4000) : text;
    }

    private record EvaluationClaim(long id, String claim, List<String> evidenceDocumentIds) {
    }

    private record SciFactDocument(String documentId, String title, List<String> abstractSentences) {
        private String content() {
            String abstractText = String.join("\n", abstractSentences).trim();
            return abstractText.isEmpty() ? title : abstractText;
        }
    }

    private record PreparedDocument(FileUpload upload, List<DocumentChunk> chunks) {
    }
}
