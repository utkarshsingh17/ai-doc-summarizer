package com.utkarsh.ai_doc_qna.document;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An uploaded file and the state of its ingestion.
 *
 * <p>Named {@code SourceDocument} rather than {@code Document} because
 * {@code org.springframework.ai.document.Document} — the chunk type — appears alongside it
 * throughout the ingestion and retrieval code.
 *
 * <p>Unlike {@link com.utkarsh.ai_doc_qna.auth.User}, this is mutable: ingestion moves a document
 * through {@code PENDING} → {@code PROCESSING} → {@code COMPLETED}/{@code FAILED} over its
 * lifetime, and each transition is persisted back via
 * {@link QdrantSourceDocumentRepository#updateStatus}.
 */
public class SourceDocument {

    /** Truncated so a pathological stack trace cannot outgrow the payload or the response. */
    private static final int MAX_ERROR_LENGTH = 2000;

    private final UUID id;
    private final String filename;
    private final String contentType;
    private final long sizeBytes;
    private final String checksum;
    private final UUID ownerId;
    private IngestionStatus status;
    private int chunkCount;
    private String errorMessage;
    private final Instant createdAt;
    private Instant updatedAt;

    private SourceDocument(UUID id, String filename, String contentType, long sizeBytes, String checksum,
                           UUID ownerId, IngestionStatus status, int chunkCount, String errorMessage,
                           Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.filename = filename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.checksum = checksum;
        this.ownerId = ownerId;
        this.status = status;
        this.chunkCount = chunkCount;
        this.errorMessage = errorMessage;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static SourceDocument create(String filename, String contentType, long sizeBytes,
                                        String checksum, UUID ownerId) {
        Instant now = Instant.now();
        return new SourceDocument(
                UUID.randomUUID(),
                Objects.requireNonNull(filename, "filename"),
                Objects.requireNonNull(contentType, "contentType"),
                sizeBytes,
                Objects.requireNonNull(checksum, "checksum"),
                Objects.requireNonNull(ownerId, "ownerId"),
                IngestionStatus.PENDING,
                0,
                null,
                now,
                now);
    }

    /** Reconstructs a document read back from the store; does not create a new one. */
    static SourceDocument restore(UUID id, String filename, String contentType, long sizeBytes, String checksum,
                                  UUID ownerId, IngestionStatus status, int chunkCount, String errorMessage,
                                  Instant createdAt, Instant updatedAt) {
        return new SourceDocument(id, filename, contentType, sizeBytes, checksum, ownerId, status, chunkCount,
                errorMessage, createdAt, updatedAt);
    }

    public void markProcessing() {
        this.status = IngestionStatus.PROCESSING;
        this.errorMessage = null;
        this.updatedAt = Instant.now();
    }

    public void markCompleted(int chunkCount) {
        this.status = IngestionStatus.COMPLETED;
        this.chunkCount = chunkCount;
        this.errorMessage = null;
        this.updatedAt = Instant.now();
    }

    public void markFailed(String reason) {
        this.status = IngestionStatus.FAILED;
        this.chunkCount = 0;
        this.errorMessage = truncate(reason);
        this.updatedAt = Instant.now();
    }

    public boolean isAnswerable() {
        return status == IngestionStatus.COMPLETED && chunkCount > 0;
    }

    public UUID getId() {
        return id;
    }

    public String getFilename() {
        return filename;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getChecksum() {
        return checksum;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public IngestionStatus getStatus() {
        return status;
    }

    public int getChunkCount() {
        return chunkCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= MAX_ERROR_LENGTH ? reason : reason.substring(0, MAX_ERROR_LENGTH);
    }
}
