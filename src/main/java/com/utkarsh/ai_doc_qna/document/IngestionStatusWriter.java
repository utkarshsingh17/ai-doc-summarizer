package com.utkarsh.ai_doc_qna.document;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Writes ingestion status transitions in their own transactions.
 *
 * <p>This is a separate bean on purpose: calling a {@code @Transactional} method from another
 * method of the same class bypasses the proxy, so the new transaction would never start and a
 * failure part-way through ingestion would roll back the {@code FAILED} marker along with it.
 */
@Component
public class IngestionStatusWriter {

    private final SourceDocumentRepository repository;

    public IngestionStatusWriter(SourceDocumentRepository repository) {
        this.repository = repository;
    }


    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void apply(UUID documentId, Consumer<SourceDocument> mutation) {
        repository.findById(documentId).ifPresent(mutation);
    }
}
