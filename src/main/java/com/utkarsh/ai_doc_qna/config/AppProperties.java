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
        @Valid Ingestion ingestion,
        @Valid Qa qa,
        @Valid Jwt jwt,
        @Valid Cors cors) {

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

    public record Jwt(
            /* Base64-encoded HMAC-SHA256 key; must decode to at least 256 bits. */
            @NotBlank String secret,
            @Positive long accessTokenExpiryMs,
            @Positive long refreshTokenExpiryMs,
            /* Cookie Secure attribute. False for local http:// dev; must be true wherever the app
             * is actually reachable over the network, or browsers will still send the cookie over
             * plain HTTP. */
            boolean cookieSecure) {
    }

    /**
     * The frontend is a separate origin (its own dev server / deployment), so cookie-carrying
     * cross-origin requests need explicit CORS — {@code Access-Control-Allow-Origin} can never be
     * {@code *} together with credentials, so every allowed origin must be listed exactly.
     */
    public record Cors(
            @NotEmpty Set<String> allowedOrigins) {
    }
}
