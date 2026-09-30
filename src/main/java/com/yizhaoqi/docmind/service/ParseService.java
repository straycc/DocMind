package com.yizhaoqi.docmind.service;

import com.yizhaoqi.docmind.model.DocumentProcessingStatus;
import com.yizhaoqi.docmind.model.FileUpload;
import com.yizhaoqi.docmind.repository.FileUploadRepository;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;

/** 解析、保守清洗、章节与子块落库的 V1 编排服务。 */
@Service
public class ParseService {
    public static final String PARSER_VERSION = "v6";
    public static final String CHUNKER_VERSION = "v7";
    private static final Logger logger = LoggerFactory.getLogger(ParseService.class);

    private final FileUploadRepository fileUploadRepository;
    private final DocumentProcessingStatusService statusService;
    private final DocumentPersistenceService persistenceService;

    public ParseService(FileUploadRepository fileUploadRepository,
                        DocumentProcessingStatusService statusService,
                        DocumentPersistenceService persistenceService) {
        this.fileUploadRepository = fileUploadRepository;
        this.statusService = statusService;
        this.persistenceService = persistenceService;
    }

    /** 文件读取和解析不占用数据库事务，解析完成后再进入短事务原子替换派生数据。 */
    public DocumentProcessingStatus parseAndSave(Long fileUploadId, InputStream fileStream) {
        FileUpload upload = fileUploadRepository.findById(fileUploadId)
                .orElseThrow(() -> new IllegalArgumentException("文件上传记录不存在: " + fileUploadId));
        statusService.markParsing(fileUploadId);

        Path temporaryFile = null;
        try {
            temporaryFile = Files.createTempFile("docmind-parse-", ".bin");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream digestStream = new DigestInputStream(fileStream, digest)) {
                Files.copy(digestStream, temporaryFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            String contentHash = Hex.encodeHexString(digest.digest());
            StructuredDocumentParser.ParseResult parsed = StructuredDocumentParser.parse(temporaryFile, upload.getFileName());
            return persistenceService.replaceParsedDocument(fileUploadId, contentHash, parsed);
        } catch (Exception exception) {
            logger.error("文档解析或切片失败: fileUploadId={}, fileName={}",
                    fileUploadId, upload.getFileName(), exception);
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

}
