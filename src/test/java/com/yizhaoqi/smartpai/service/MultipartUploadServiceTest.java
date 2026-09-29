package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.dto.MultipartUploadDtos.InitRequest;
import com.yizhaoqi.smartpai.dto.MultipartUploadDtos.InitResponse;
import com.yizhaoqi.smartpai.dto.MultipartUploadDtos.StatusResponse;
import com.yizhaoqi.smartpai.model.DocumentProcessingStatus;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.storage.MultipartMinioClient;
import io.minio.messages.Part;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MultipartUploadServiceTest {
    private MultipartMinioClient minioClient;
    private FileUploadRepository repository;
    private UserService userService;
    private MultipartUploadService service;

    @BeforeEach
    void setUp() {
        minioClient = Mockito.mock(MultipartMinioClient.class);
        repository = Mockito.mock(FileUploadRepository.class);
        userService = Mockito.mock(UserService.class);
        service = new MultipartUploadService(minioClient, repository, userService,
                new FileTypeValidationService(), "uploads", 15);
    }

    @Test
    void initiatesNativeMultipartAndPersistsOnlySessionMetadata() throws Exception {
        InitRequest request = new InitRequest("manual.pdf", 20L * 1024 * 1024,
                "0123456789abcdef0123456789abcdef", "application/pdf", null, null, false);
        when(repository.findByFileMd5AndUserId(request.fileMd5(), "u1")).thenReturn(Optional.empty());
        when(userService.getUserPrimaryOrg("u1")).thenReturn("engineering");
        when(minioClient.initiate(any(), any(), any())).thenReturn("minio-upload-id");
        when(repository.save(any())).thenAnswer(invocation -> {
            FileUpload upload = invocation.getArgument(0);
            upload.setId(42L);
            return upload;
        });

        InitResponse response = service.initiate(request, "u1");

        assertEquals(42L, response.fileUploadId());
        assertEquals("minio-upload-id", response.uploadId());
        assertEquals(16L * 1024 * 1024, response.partSize());
        assertEquals(2, response.totalParts());
        verify(minioClient).initiate(any(), any(), any());
    }

    @Test
    void derivesResumeProgressFromMinioParts() throws Exception {
        FileUpload upload = uploadingFile();
        Part first = Mockito.mock(Part.class);
        when(first.partNumber()).thenReturn(1);
        when(first.etag()).thenReturn("etag-1");
        when(first.partSize()).thenReturn(16L * 1024 * 1024);
        when(repository.findByIdAndUserId(7L, "u1")).thenReturn(Optional.of(upload));
        when(minioClient.parts("uploads", upload.getObjectKey(), upload.getMinioUploadId()))
                .thenReturn(List.of(first));

        StatusResponse status = service.status(7L, "u1");

        assertEquals(List.of(1), status.uploadedParts().stream().map(part -> part.partNumber()).toList());
        assertEquals(80.0, status.progress());
    }

    @Test
    void refusesCompletionWhenAnyPartIsMissing() throws Exception {
        FileUpload upload = uploadingFile();
        Part first = Mockito.mock(Part.class);
        when(first.partNumber()).thenReturn(1);
        when(first.partSize()).thenReturn(16L * 1024 * 1024);
        when(repository.findOwnedByIdForUpdate(7L, "u1")).thenReturn(Optional.of(upload));
        when(minioClient.parts("uploads", upload.getObjectKey(), upload.getMinioUploadId()))
                .thenReturn(List.of(first));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.complete(7L, "u1"));

        assertTrue(error.getMessage().contains("未上传完整"));
        verify(minioClient, never()).finish(any(), any(), any(), any());
    }

    @Test
    void deletingIncompleteUploadAbortsMinioAndRemovesDatabaseRecord() throws Exception {
        FileUpload upload = uploadingFile();
        when(repository.findOwnedByIdForUpdate(7L, "u1")).thenReturn(Optional.of(upload));

        service.deleteIncompleteUpload(7L, "u1");

        verify(minioClient).abort("uploads", upload.getObjectKey(), "upload-id");
        verify(repository).delete(upload);
        verify(repository, never()).save(upload);
    }

    private FileUpload uploadingFile() {
        FileUpload upload = new FileUpload();
        upload.setId(7L);
        upload.setFileMd5("0123456789abcdef0123456789abcdef");
        upload.setFileName("manual.pdf");
        upload.setTotalSize(20L * 1024 * 1024);
        upload.setStatus(MultipartUploadService.STATUS_UPLOADING);
        upload.setObjectKey("documents/id/manual.pdf");
        upload.setUploadProtocol(MultipartUploadService.PROTOCOL);
        upload.setMinioUploadId("upload-id");
        upload.setPartSize(16L * 1024 * 1024);
        upload.setTotalParts(2);
        upload.setUserId("u1");
        upload.setProcessingStatus(DocumentProcessingStatus.UPLOADED);
        return upload;
    }
}
