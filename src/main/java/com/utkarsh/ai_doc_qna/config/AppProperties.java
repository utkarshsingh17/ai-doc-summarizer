package com.utkarsh.ai_doc_qna.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Set;

/**
 * Typed binding for the {@code app.*} configuration block. Validated at startup so a bad
 * threshold or a missing bucket fails the context rather than surfacing at request time.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @Valid Storage storage,
        @Valid Ingestion ingestion,
        @Valid Qa qa) {

    public record Storage(
            @NotBlank String endpoint,
            @NotBlank String region,
            @NotBlank String bucket,
            @NotBlank String accessKey,
            @NotBlank String secretKey,
            /* MinIO addresses buckets by path; real AWS S3 uses virtual-host style. */
            boolean pathStyleAccess) {
    }

    public record Ingestion(
            @Min(100) int chunkSizeTokens,
            @Min(1) int minChunkSizeChars,
            @Positive long maxFileSizeBytes,
            @NotEmpty Set<String> allowedContentTypes) {
    }

    public record Qa(
            @Min(1) int topK,
            @DecimalMin("0.0") @DecimalMax("1.0") double similarityThreshold,
            @Min(50) int maxSnippetChars) {
    }
}
