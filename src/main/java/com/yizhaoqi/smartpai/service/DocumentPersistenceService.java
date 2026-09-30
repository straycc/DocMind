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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/** 将解析结果原子替换为新的Section和Chunk；不执行耗时的文件解析。 */
@Service
public class DocumentPersistenceService {
    private static final Logger logger = LoggerFactory.getLogger(DocumentPersistenceService.class);

    private final FileUploadRepository fileUploadRepository;
    private final DocumentSectionRepository sectionRepository;
    private final DocumentChunkRepository chunkRepository;

    public DocumentPersistenceService(FileUploadRepository fileUploadRepository,
                                      DocumentSectionRepository sectionRepository,
                                      DocumentChunkRepository chunkRepository) {
        this.fileUploadRepository = fileUploadRepository;
        this.sectionRepository = sectionRepository;
        this.chunkRepository = chunkRepository;
    }

    @Transactional
    public DocumentProcessingStatus replaceParsedDocument(Long fileUploadId, String contentHash,
                                                           StructuredDocumentParser.ParseResult parsed) {
        FileUpload upload = fileUploadRepository.findById(fileUploadId)
                .orElseThrow(() -> new IllegalArgumentException("文件上传记录不存在: " + fileUploadId));
        clearDerivedData(fileUploadId);
        upload.setContentHash(contentHash);
        upload.setParserVersion(ParseService.PARSER_VERSION);
        upload.setChunkerVersion(ParseService.CHUNKER_VERSION);

        if (parsed.needsOcr()) {
            upload.setProcessingStatus(DocumentProcessingStatus.NEEDS_OCR);
            upload.setProcessingError("PDF 未检测到有效文本层，等待 OCR 处理");
            fileUploadRepository.save(upload);
            return DocumentProcessingStatus.NEEDS_OCR;
        }

        rebuildDerivedData(upload, parsed.elements());
        upload.setProcessingStatus(DocumentProcessingStatus.CHUNKED);
        upload.setProcessingError(null);
        fileUploadRepository.save(upload);
        return DocumentProcessingStatus.CHUNKED;
    }

    private void rebuildDerivedData(FileUpload upload, List<DocumentElement> elements) {
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
                chunk.setChunkerVersion(ParseService.CHUNKER_VERSION);
                chunks.add(chunk);
            }
        }
        chunkRepository.saveAll(chunks);
        logQuality(upload, elements, sectionDrafts, chunks);
    }

    private void logQuality(FileUpload upload, List<DocumentElement> elements,
                            List<DocumentSectionPlanner.SectionDraft> sectionDrafts,
                            List<DocumentChunk> chunks) {
        logger.info("文件完成解析与切片: fileUploadId={}, sections={}, chunks={}",
                upload.getId(), sectionDrafts.size(), chunks.size());
        int titledSections = (int) sectionDrafts.stream().filter(section -> section.titlePath() != null
                && !section.titlePath().isBlank()).count();
        int titleElements = (int) elements.stream()
                .filter(element -> element.getType() == DocumentElementType.TITLE).count();
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
}
