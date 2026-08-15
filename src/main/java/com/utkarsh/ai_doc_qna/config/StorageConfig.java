package com.utkarsh.ai_doc_qna.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

import java.net.URI;

@Configuration
public class StorageConfig {

    private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

    @Bean
    public S3Client s3Client(AppProperties properties) {
        AppProperties.Storage storage = properties.storage();
        return S3Client.builder()
                .endpointOverride(URI.create(storage.endpoint()))
                .region(Region.of(storage.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(storage.accessKey(), storage.secretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        // MinIO serves buckets at /<bucket>/<key>; virtual-host style would
                        // resolve to a hostname that does not exist locally.
                        .pathStyleAccessEnabled(storage.pathStyleAccess())
                        .build())
                .build();
    }

    /**
     * Creates the bucket on first run so a fresh MinIO volume does not fail every upload.
     * Against real S3 the bucket is normally provisioned out of band, and this is a no-op.
     */
    @Bean
    public ApplicationRunner bucketInitializer(S3Client s3Client, AppProperties properties) {
        String bucket = properties.storage().bucket();
        return args -> {
            try {
                s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
                log.info("Using existing object storage bucket '{}'", bucket);
            } catch (NoSuchBucketException ex) {
                s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                log.info("Created object storage bucket '{}'", bucket);
            }
        };
    }
}
