package com.yizhaoqi.docmind.service;

import com.yizhaoqi.docmind.model.DocumentProcessingStatus;
import com.yizhaoqi.docmind.model.FileUpload;
import com.yizhaoqi.docmind.repository.FileUploadRepository;
import org.junit.jupiter.api.Test;

import java.net.SocketException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentProcessingStatusServiceTest {
    private final FileUploadRepository repository = mock(FileUploadRepository.class);
    private final DocumentProcessingStatusService service = new DocumentProcessingStatusService(repository);

    @Test
    void marksTransientFailureRetryingAndKeepsRootCause() {
        FileUpload upload = upload(DocumentProcessingStatus.EMBEDDING);
        when(repository.findById(7L)).thenReturn(Optional.of(upload));

        service.markRetrying(7L, new IllegalStateException("wrapper", new SocketException("Connection reset")));

        assertEquals(DocumentProcessingStatus.RETRYING, upload.getProcessingStatus());
        assertEquals("SocketException: Connection reset", upload.getProcessingError());
    }

    @Test
    void doesNotRegressReadyDocumentWhenLateFailureArrives() {
        FileUpload upload = upload(DocumentProcessingStatus.READY);
        when(repository.findById(7L)).thenReturn(Optional.of(upload));

        service.markFailed(7L, new RuntimeException("late failure"));

        assertEquals(DocumentProcessingStatus.READY, upload.getProcessingStatus());
    }

    private FileUpload upload(DocumentProcessingStatus status) {
        FileUpload upload = new FileUpload();
        upload.setId(7L);
        upload.setProcessingStatus(status);
        return upload;
    }
}
