package com.utkarsh.ai_doc_qna.storage;

import com.utkarsh.ai_doc_qna.common.exception.StorageException;
import com.utkarsh.ai_doc_qna.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.core.exception.SdkException;

import java.io.InputStream;

/**
 * S3 adapter. Works unchanged against MinIO and against real AWS S3 — the only difference is
 * the endpoint and path-style setting applied when the {@link S3Client} bean is built.
 */
@Component
public class S3DocumentStorage implements DocumentStorage {

    private static final Logger log = LoggerFactory.getLogger(S3DocumentStorage.class);

    private final S3Client s3Client;
    private final String bucket;

    public S3DocumentStorage(S3Client s3Client, AppProperties properties) {
        this.s3Client = s3Client;
        this.bucket = properties.storage().bucket();
    }

    @Override
    public String store(String key, InputStream content, long contentLength, String contentType) {
        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(contentType)
                            .contentLength(contentLength)
                            .build(),
                    // Length is passed explicitly so the SDK streams rather than buffering the
                    // whole file in memory.
                    RequestBody.fromInputStream(content, contentLength));
            log.debug("Stored object {} ({} bytes)", key, contentLength);
            return key;
        } catch (SdkException ex) {
            throw new StorageException("Failed to store object " + key, ex);
        }
    }

    @Override
    public InputStream retrieve(String key) {
        try {
            return s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (SdkException ex) {
            throw new StorageException("Failed to retrieve object " + key, ex);
        }
    }

    @Override
    public void delete(String key) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            log.debug("Deleted object {}", key);
        } catch (S3Exception ex) {
            // Deleting an object that is already gone is not a failure for the caller.
            if (ex.statusCode() == 404) {
                log.warn("Object {} was already absent", key);
                return;
            }
            throw new StorageException("Failed to delete object " + key, ex);
        } catch (SdkException ex) {
            throw new StorageException("Failed to delete object " + key, ex);
        }
    }
}
