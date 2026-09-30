package com.yizhaoqi.docmind.storage;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import io.minio.CreateMultipartUploadResponse;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListPartsResponse;
import io.minio.MinioAsyncClient;
import io.minio.MinioClient;
import io.minio.ObjectWriteResponse;
import io.minio.StatObjectArgs;
import io.minio.http.Method;
import io.minio.messages.ListPartsResult;
import io.minio.messages.Part;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Exposes the S3 multipart primitives that MinIO Java SDK keeps protected.
 * The storage client may use an internal endpoint while the signing client
 * must use the browser-reachable endpoint because the host participates in
 * the S3 signature.
 */
public class MultipartMinioClient extends MinioAsyncClient {
    private static final int LIST_PAGE_SIZE = 1000;
    private final MinioClient signingClient;

    public MultipartMinioClient(MinioAsyncClient storageClient, MinioClient signingClient) {
        super(storageClient);
        this.signingClient = signingClient;
    }

    public String initiate(String bucket, String objectKey, String contentType) throws Exception {
        Multimap<String, String> headers = HashMultimap.create();
        if (contentType != null && !contentType.isBlank()) {
            headers.put("Content-Type", contentType);
        }
        CreateMultipartUploadResponse response = createMultipartUpload(
                bucket, null, objectKey, headers, HashMultimap.create());
        return response.result().uploadId();
    }

    public String presignPart(String bucket, String objectKey, String uploadId,
                              int partNumber, int expiryMinutes) throws Exception {
        return signingClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .method(Method.PUT)
                        .bucket(bucket)
                        .object(objectKey)
                        .expiry(expiryMinutes, TimeUnit.MINUTES)
                        .extraQueryParams(Map.of(
                                "uploadId", uploadId,
                                "partNumber", Integer.toString(partNumber)))
                        .build());
    }

    public String presignGet(String bucket, String objectKey, int expiryMinutes) throws Exception {
        return signingClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .method(Method.GET)
                        .bucket(bucket)
                        .object(objectKey)
                        .expiry(expiryMinutes, TimeUnit.MINUTES)
                        .build());
    }

    public long statSize(String bucket, String objectKey) throws Exception {
        return statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build()).get().size();
    }

    public List<Part> parts(String bucket, String objectKey, String uploadId) throws Exception {
        List<Part> parts = new ArrayList<>();
        int marker = 0;
        boolean truncated;
        do {
            ListPartsResponse response = listParts(bucket, null, objectKey,
                    LIST_PAGE_SIZE, marker, uploadId, HashMultimap.create(), HashMultimap.create());
            ListPartsResult result = response.result();
            if (result.partList() != null) {
                parts.addAll(result.partList());
            }
            truncated = result.isTruncated();
            marker = result.nextPartNumberMarker();
        } while (truncated);
        parts.sort(Comparator.comparingInt(Part::partNumber));
        return parts;
    }

    public ObjectWriteResponse finish(String bucket, String objectKey, String uploadId,
                                      List<Part> completedParts) throws Exception {
        Part[] parts = completedParts.stream()
                .map(part -> new Part(part.partNumber(), part.etag()))
                .toArray(Part[]::new);
        return completeMultipartUpload(bucket, null, objectKey, uploadId, parts,
                HashMultimap.create(), HashMultimap.create());
    }

    public void abort(String bucket, String objectKey, String uploadId) throws Exception {
        abortMultipartUpload(bucket, null, objectKey, uploadId,
                HashMultimap.create(), HashMultimap.create());
    }
}
