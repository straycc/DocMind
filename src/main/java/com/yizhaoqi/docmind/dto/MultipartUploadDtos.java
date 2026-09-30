package com.yizhaoqi.docmind.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.util.List;

public final class MultipartUploadDtos {
    private MultipartUploadDtos() {
    }

    public record InitRequest(
            @NotBlank String fileName,
            @Positive long totalSize,
            @NotBlank @Pattern(regexp = "(?i)^[a-f0-9]{32}$", message = "fileMd5必须是32位十六进制MD5") String fileMd5,
            String contentType,
            @Positive Long partSize,
            String orgTag,
            boolean isPublic) {
    }

    public record InitResponse(
            long fileUploadId,
            String uploadId,
            String objectKey,
            long partSize,
            int totalParts,
            String status) {
    }

    public record PartView(int partNumber, String etag, long size) {
    }

    public record StatusResponse(
            long fileUploadId,
            String status,
            long partSize,
            int totalParts,
            List<PartView> uploadedParts,
            double progress) {
    }

    public record PresignResponse(int partNumber, String url, int expiresInSeconds) {
    }

    public record CompleteResponse(
            long fileUploadId,
            String objectKey,
            String objectUrl,
            String status,
            boolean newlyCompleted) {
    }
}
