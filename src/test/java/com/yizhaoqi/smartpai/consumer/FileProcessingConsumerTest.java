package com.yizhaoqi.smartpai.consumer;

import com.yizhaoqi.smartpai.model.DocumentProcessingStatus;
import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.DocumentChunkRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.service.DocumentProcessingStatusService;
import com.yizhaoqi.smartpai.service.ParseService;
import com.yizhaoqi.smartpai.service.VectorizationService;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileProcessingConsumerTest {
    private final ParseService parseService = mock(ParseService.class);
    private final VectorizationService vectorizationService = mock(VectorizationService.class);
    private final FileUploadRepository uploadRepository = mock(FileUploadRepository.class);
    private final DocumentChunkRepository chunkRepository = mock(DocumentChunkRepository.class);
    private final DocumentProcessingStatusService statusService = mock(DocumentProcessingStatusService.class);
    private final MinioClient minioClient = mock(MinioClient.class);
    private final FileProcessingConsumer consumer = new FileProcessingConsumer(parseService, vectorizationService,
            uploadRepository, chunkRepository, statusService, minioClient, "uploads");

    @Test
    void resumesFromPersistedChunksWithoutParsingPdfAgain() throws Exception {
        FileUpload upload = retryingUpload();
        when(uploadRepository.findById(7L)).thenReturn(Optional.of(upload));
        when(chunkRepository.existsByFileUploadId(7L)).thenReturn(true);

        consumer.processTask(task());

        verify(minioClient, never()).getObject(org.mockito.ArgumentMatchers.any());
        verify(parseService, never()).parseAndSave(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any());
        verify(vectorizationService).vectorize(7L);
    }

    @Test
    void transientFailureRemainsRetryableInsteadOfBecomingFinalFailure() {
        FileUpload upload = retryingUpload();
        when(uploadRepository.findById(7L)).thenReturn(Optional.of(upload));
        when(chunkRepository.existsByFileUploadId(7L)).thenReturn(true);
        RuntimeException failure = new RuntimeException("temporary embedding failure");
        doThrow(failure).when(vectorizationService).vectorize(7L);

        assertThrows(IllegalStateException.class, () -> consumer.processTask(task()));

        verify(statusService).markRetrying(7L, failure);
        verify(statusService, never()).markFailed(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.any(Throwable.class));
    }

    private FileUpload retryingUpload() {
        FileUpload upload = new FileUpload();
        upload.setId(7L);
        upload.setFileMd5("md5");
        upload.setObjectKey("documents/7.pdf");
        upload.setChunkerVersion(ParseService.CHUNKER_VERSION);
        upload.setProcessingStatus(DocumentProcessingStatus.RETRYING);
        return upload;
    }

    private FileProcessingTask task() {
        return new FileProcessingTask(7L, "documents/7.pdf", "md5", "test.pdf",
                "1", "DEFAULT", false);
    }
}
