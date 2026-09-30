package com.yizhaoqi.docmind.config;

import com.yizhaoqi.docmind.storage.MultipartMinioClient;
import io.minio.MinioAsyncClient;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

    @Value("${minio.endpoint}")
    private String endpoint;

    @Value("${minio.accessKey}")
    private String accessKey;

    @Value("${minio.secretKey}")
    private String secretKey;

    @Value("${minio.publicUrl}")
    private String publicUrl;


    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

    @Bean
    public MultipartMinioClient multipartMinioClient() {
        MinioAsyncClient storageClient = MinioAsyncClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        String signingEndpoint = publicUrl == null || publicUrl.isBlank() ? endpoint : publicUrl;
        MinioClient signingClient = MinioClient.builder()
                .endpoint(signingEndpoint)
                .credentials(accessKey, secretKey)
                .build();
        return new MultipartMinioClient(storageClient, signingClient);
    }

    @Bean
    public String minioPublicUrl() {
        return publicUrl;
    }
}
