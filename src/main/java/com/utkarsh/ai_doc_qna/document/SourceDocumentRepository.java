package com.utkarsh.ai_doc_qna.document;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

public interface SourceDocumentRepository {

    /** Creates a new document, storing the raw uploaded bytes alongside its metadata. */
    SourceDocument save(SourceDocument document, byte[] content);

    /**
     * Persists an ingestion-status transition on an existing document, without touching its
     * stored content.
     */
    void updateStatus(SourceDocument document);

    Optional<SourceDocument> findById(UUID id);

    Optional<SourceDocument> findByIdAndOwnerId(UUID id, UUID ownerId);

    /**
     * Scoped to the owner: identical content uploaded by two different users is not a duplicate
     * from the app's point of view, only a coincidence.
     */
    Optional<SourceDocument> findByOwnerIdAndChecksum(UUID ownerId, String checksum);

    long countByOwnerId(UUID ownerId);

    long countByOwnerIdAndStatus(UUID ownerId, IngestionStatus status);

    Page<SourceDocument> findAllByOwnerId(UUID ownerId, Pageable pageable);

    /** The raw bytes of the originally uploaded file. */
    byte[] loadContent(UUID id);

    /**
     * Drops the stored file bytes once they are no longer needed — after the document has been
     * successfully split, embedded, and stored as chunks, there is nothing left that needs the
     * original file, and there is no reason to keep paying to store it.
     */
    void clearContent(UUID id);

    void delete(SourceDocument document);

    void deleteAll();
}
