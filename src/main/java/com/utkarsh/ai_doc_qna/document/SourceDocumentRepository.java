package com.utkarsh.ai_doc_qna.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SourceDocumentRepository extends JpaRepository<SourceDocument, UUID> {

    Optional<SourceDocument> findByChecksum(String checksum);

    long countByStatus(IngestionStatus status);
}
