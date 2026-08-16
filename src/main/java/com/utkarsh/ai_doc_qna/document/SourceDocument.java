package com.utkarsh.ai_doc_qna.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An uploaded file and the state of its ingestion.
 *
 * <p>Named {@code SourceDocument} rather than {@code Document} because
 * {@code org.springframework.ai.document.Document} — the chunk type — appears alongside it
 * throughout the ingestion and retrieval code.
 */
@Entity
@Table(name = "source_documents")
public class SourceDocument {

    /** Truncated so a pathological stack trace cannot outgrow the column or the response. */
    private static final int MAX_ERROR_LENGTH = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Version
    private Long version;

    @Column(nullable = false, length = 512)
    private String filename;

    @Column(name = "content_type", nullable = false, length = 128)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64, updatable = false)
    private String checksum;

    @Column(name = "storage_key", nullable = false, length = 1024)
    private String storageKey;

    /**
     * Nullable in the schema only because rows created before ownership existed have none —
     * {@link #create} always requires it for anything uploaded from here on.
     */
    @Column(name = "owner_id")
    private UUID ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private IngestionStatus status;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    @Column(name = "error_message")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by Hibernate; use {@link #create} to build a new document. */
    protected SourceDocument() {
    }

    public static SourceDocument create(String filename, String contentType, long sizeBytes,
                                        String checksum, String storageKey, UUID ownerId) {
        SourceDocument document = new SourceDocument();
        document.filename = Objects.requireNonNull(filename, "filename");
        document.contentType = Objects.requireNonNull(contentType, "contentType");
        document.sizeBytes = sizeBytes;
        document.checksum = Objects.requireNonNull(checksum, "checksum");
        document.storageKey = Objects.requireNonNull(storageKey, "storageKey");
        document.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        document.status = IngestionStatus.PENDING;
        document.chunkCount = 0;
        return document;
    }

    public void markProcessing() {
        this.status = IngestionStatus.PROCESSING;
        this.errorMessage = null;
    }

    public void markCompleted(int chunkCount) {
        this.status = IngestionStatus.COMPLETED;
        this.chunkCount = chunkCount;
        this.errorMessage = null;
    }

    public void markFailed(String reason) {
        this.status = IngestionStatus.FAILED;
        this.chunkCount = 0;
        this.errorMessage = truncate(reason);
    }

    public boolean isAnswerable() {
        return status == IngestionStatus.COMPLETED && chunkCount > 0;
    }

    public UUID getId() {
        return id;
    }

    public Long getVersion() {
        return version;
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

    public String getStorageKey() {
        return storageKey;
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
