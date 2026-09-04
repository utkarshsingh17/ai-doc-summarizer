package com.utkarsh.ai_doc_qna.document;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Applies and persists an ingestion status transition.
 *
 * <p>A separate bean mainly for symmetry with the mutation call sites in {@link IngestionService}
 * — there is no transactional proxy to bypass here the way there was under JPA, since
 * {@link QdrantSourceDocumentRepository#updateStatus} is a single, immediately-visible point
 * update rather than something that needs its own transaction to survive a later failure.
 */
@Component
public class IngestionStatusWriter {

    private final SourceDocumentRepository repository;

    public IngestionStatusWriter(SourceDocumentRepository repository) {
        this.repository = repository;
    }


    public void apply(UUID documentId, Consumer<SourceDocument> mutation) {
        repository.findById(documentId).ifPresent(document -> {
            mutation.accept(document);
            repository.updateStatus(document);
        });
    }
}
