package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.entity.DocumentElement;
import com.yizhaoqi.smartpai.model.DocumentChunk;
import com.yizhaoqi.smartpai.model.DocumentElementType;
import com.yizhaoqi.smartpai.model.DocumentProcessingStatus;
import com.yizhaoqi.smartpai.model.DocumentSection;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.DocumentChunkRepository;
import com.yizhaoqi.smartpai.repository.DocumentSectionRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** 解析、保守清洗、章节与子块落库的 V1 编排服务。 */
@Service
public class ParseService {
    public static final String PARSER_VERSION = "v6";
    public static final String CHUNKER_VERSION = "v7";
    private static final Logger logger = LoggerFactory.getLogger(ParseService.class);

    private final FileUploadRepository fileUploadRepository;
    private final DocumentSectionRepository sectionRepository;
    private final DocumentChunkRepository chunkRepository;

    public ParseService(FileUploadRepository fileUploadRepository, DocumentSectionRepository sectionRepository,
                        DocumentChunkRepository chunkRepository) {
        this.fileUploadRepository = fileUploadRepository;
        this.sectionRepository = sectionRepository;
        this.chunkRepository = chunkRepository;
    }

    /** 同一文件和版本在重试时先清空派生数据，再用确定性的顺序号重建。 */
    @Transactional(noRollbackFor = IllegalStateException.class)
    public DocumentProcessingStatus parseAndSave(Long fileUploadId, InputStream fileStream) {
        FileUpload upload = fileUploadRepository.findById(fileUploadId)
                .orElseThrow(() -> new IllegalArgumentException("文件上传记录不存在: " + fileUploadId));
        upload.setProcessingStatus(DocumentProcessingStatus.PARSING);
        upload.setProcessingError(null);
        fileUploadRepository.save(upload);

        Path temporaryFile = null;
        try {
            temporaryFile = Files.createTempFile("smartpai-parse-", ".bin");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream digestStream = new DigestInputStream(fileStream, digest)) {
                Files.copy(digestStream, temporaryFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            upload.setContentHash(Hex.encodeHexString(digest.digest()));
            StructuredDocumentParser.ParseResult parsed = StructuredDocumentParser.parse(temporaryFile, upload.getFileName());
            if (parsed.needsOcr()) {
                clearDerivedData(fileUploadId);
                upload.setParserVersion(PARSER_VERSION);
                upload.setChunkerVersion(CHUNKER_VERSION);
                upload.setProcessingStatus(DocumentProcessingStatus.NEEDS_OCR);
                upload.setProcessingError("PDF 未检测到有效文本层，等待 OCR 处理");
                fileUploadRepository.save(upload);
                return DocumentProcessingStatus.NEEDS_OCR;
            }
            rebuildDerivedData(upload, parsed.elements());
            upload.setParserVersion(PARSER_VERSION);
            upload.setChunkerVersion(CHUNKER_VERSION);
            upload.setProcessingStatus(DocumentProcessingStatus.CHUNKED);
            upload.setProcessingError(null);
            fileUploadRepository.save(upload);
            return DocumentProcessingStatus.CHUNKED;
        } catch (Exception exception) {
            upload.setProcessingStatus(DocumentProcessingStatus.FAILED);
            upload.setProcessingError(limitError(exception));
            fileUploadRepository.save(upload);
            throw new IllegalStateException("文档解析或切片失败: " + upload.getFileName(), exception);
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (Exception exception) {
                    logger.warn("无法删除解析临时文件: {}", temporaryFile, exception);
                }
            }
        }
    }

    /** 保留旧调用入口，但统一落到新的文件事实记录。 */
    public DocumentProcessingStatus parseAndSave(String fileMd5, InputStream fileStream,
                                                 String userId, String orgTag, boolean isPublic) {
        FileUpload upload = fileUploadRepository.findByFileMd5(fileMd5)
                .orElseThrow(() -> new IllegalArgumentException("文件上传记录不存在: " + fileMd5));
        return parseAndSave(upload.getId(), fileStream);
    }

    public DocumentProcessingStatus parseAndSave(String fileMd5, InputStream fileStream) {
        return parseAndSave(fileMd5, fileStream, null, null, false);
    }

    private void rebuildDerivedData(FileUpload upload, List<DocumentElement> elements) {
        clearDerivedData(upload.getId());
        List<DocumentSectionPlanner.SectionDraft> sectionDrafts = DocumentSectionPlanner.plan(elements);
        List<DocumentChunk> chunks = new ArrayList<>();
        int ordinal = 0;
        int sectionOrdinal = 0;
        for (DocumentSectionPlanner.SectionDraft sectionDraft : sectionDrafts) {
            DocumentSection section = newSection(upload.getId(), ++sectionOrdinal, sectionDraft.titlePath(),
                    sectionDraft.pageStart(), sectionDraft.pageEnd());
            section = sectionRepository.save(section);
            String embeddingPrefix = DocumentEmbeddingTextBuilder.buildPrefix(upload.getFileName(), section.getTitlePath());
            for (DocumentChunker.ChunkDraft draft : DocumentChunker.chunk(embeddingPrefix, sectionDraft.elements())) {
                DocumentChunk chunk = new DocumentChunk();
                chunk.setFileUploadId(upload.getId());
                chunk.setSectionId(section.getId());
                chunk.setOrdinal(++ordinal);
                chunk.setTextContent(draft.text());
                chunk.setTitlePath(section.getTitlePath());
                chunk.setPageStart(draft.pageStart());
                chunk.setPageEnd(draft.pageEnd());
                chunk.setSourceLocator(draft.sourceLocator());
                String embeddingText = DocumentEmbeddingTextBuilder.build(upload.getFileName(), chunk.getTitlePath(),
                        chunk.getTextContent());
                chunk.setEstimatedTokenCount(CjkTokenEstimator.estimate(embeddingText));
                chunk.setContentHash(DigestUtils.sha256Hex(chunk.getTextContent()));
                chunk.setChunkerVersion(CHUNKER_VERSION);
                chunks.add(chunk);
            }
        }
        chunkRepository.saveAll(chunks);
        logger.info("文件完成解析与切片: fileUploadId={}, sections={}, chunks={}", upload.getId(), sectionDrafts.size(), chunks.size());
        int titledSections = (int) sectionDrafts.stream().filter(section -> section.titlePath() != null
                && !section.titlePath().isBlank()).count();
        int titleElements = (int) elements.stream().filter(element -> element.getType() == DocumentElementType.TITLE).count();
        int shortChunks = (int) chunks.stream().filter(chunk -> chunk.getEstimatedTokenCount() < 100).count();
        int averageTokens = chunks.isEmpty() ? 0 : (int) Math.round(chunks.stream()
                .mapToInt(DocumentChunk::getEstimatedTokenCount).average().orElse(0));
        logger.info("文档解析质量: fileUploadId={}, elements={}, acceptedTitleElements={}, titledSections={}, "
                        + "chunks={}, averageChunkTokens={}, shortChunks={}",
                upload.getId(), elements.size(), titleElements, titledSections, chunks.size(), averageTokens, shortChunks);
    }

    private void clearDerivedData(Long fileUploadId) {
        chunkRepository.deleteByFileUploadId(fileUploadId);
        sectionRepository.deleteByFileUploadId(fileUploadId);
    }

    private DocumentSection newSection(Long fileUploadId, int ordinal, String titlePath,
                                       Integer pageStart, Integer pageEnd) {
        DocumentSection section = new DocumentSection();
        section.setFileUploadId(fileUploadId);
        section.setOrdinal(ordinal);
        section.setTitlePath(titlePath);
        section.setPageStart(pageStart);
        section.setPageEnd(pageEnd);
        return section;
    }

    private String limitError(Exception exception) {
        String text = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        return text.length() > 4000 ? text.substring(0, 4000) : text;
    }

}
