package io.contextguard.service;

import io.minio.*;
import io.minio.errors.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.io.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class MinIOService {

    private final MinioClient minioClient;

    @Value("${contextguard.minio.bucket}")
    private String bucketName;

    public void ensureBucketExists() {
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucketName).build()
            );
            if (!exists) {
                minioClient.makeBucket(
                        MakeBucketArgs.builder().bucket(bucketName).build()
                );
                log.info("Created MinIO bucket: {}", bucketName);
            }
        } catch (Exception e) {
            log.error("Failed to ensure bucket exists", e);
        }
    }

    public String uploadDiff(String key, String diffContent) {
        try {
            ensureBucketExists();

            byte[] bytes = diffContent.getBytes();
            ByteArrayInputStream stream = new ByteArrayInputStream(bytes);

            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(key)
                            .stream(stream, bytes.length, -1)
                            .contentType("text/plain")
                            .build()
            );

            log.info("Uploaded diff to MinIO: {}", key);
            return key;

        } catch (Exception e) {
            log.error("Failed to upload diff to MinIO", e);
            throw new RuntimeException("Failed to upload diff", e);
        }
    }

    public String downloadDiff(String key) {
        try {
            InputStream stream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucketName)
                            .object(key)
                            .build()
            );

            return new String(stream.readAllBytes());

        } catch (Exception e) {
            log.error("Failed to download diff from MinIO", e);
            return null;
        }
    }
}

