package io.contextguard.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinIOConfig {
    @Value("${contextguard.minio.endpoint}")
    private String endpoint;

    @Value("${contextguard.minio.access-key}")
    private String accessKey;

    @Value("${contextguard.minio.secret-key}")
    private String secretKey;

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                       .endpoint(endpoint)
                       .credentials(accessKey, secretKey)
                       .build();
    }
}
