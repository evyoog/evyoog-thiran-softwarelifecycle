package com.vyoog.attachments;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * VYB-0123: attachment bytes live in an S3-compatible object store — MinIO locally
 * (docker-compose.yml), matching whatever real object store this points at in a real
 * deployment. Untested against a real MinIO in this session — no Docker in the
 * sandbox this was built in; see BUILD-REGISTER.md.
 */
@Configuration
public class StorageConfig {

    @Bean
    public S3Client s3Client(
            @Value("${vyoog.storage.endpoint:http://localhost:9000}") String endpoint,
            @Value("${vyoog.storage.access-key:minio}") String accessKey,
            @Value("${vyoog.storage.secret-key:minio123}") String secretKey,
            @Value("${vyoog.storage.region:us-east-1}") String region) {
        return S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(region))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
            .forcePathStyle(true) // MinIO needs path-style (bucket in the path, not a subdomain)
            .build();
    }
}
