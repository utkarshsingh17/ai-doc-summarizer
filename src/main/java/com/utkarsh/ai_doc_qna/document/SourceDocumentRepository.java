package com.utkarsh.ai_doc_qna.document;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SourceDocumentRepository extends JpaRepository<SourceDocument, UUID> {

    /**
     * Scoped to the owner: identical content uploaded by two different users is not a duplicate
     * from the app's point of view, only a coincidence.
     */
    Optional<SourceDocument> findByOwnerIdAndChecksum(UUID ownerId, String checksum);

    long countByStatus(IngestionStatus status);

    long countByOwnerId(UUID ownerId);

    long countByOwnerIdAndStatus(UUID ownerId, IngestionStatus status);

    Page<SourceDocument> findAllByOwnerId(UUID ownerId, Pageable pageable);

    Optional<SourceDocument> findByIdAndOwnerId(UUID id, UUID ownerId);
}
