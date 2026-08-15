package com.utkarsh.ai_doc_qna.common.exception;

import java.util.UUID;

/**
 * Raised when a file whose checksum already exists is uploaded again. Ingesting it twice would
 * double every chunk in the vector store and let one document dominate retrieval.
 */
public class DuplicateDocumentException extends RuntimeException {

    private final UUID existingDocumentId;

    public DuplicateDocumentException(UUID existingDocumentId, String filename) {
        super("'%s' has already been uploaded as document %s".formatted(filename, existingDocumentId));
        this.existingDocumentId = existingDocumentId;
    }

    public UUID getExistingDocumentId() {
        return existingDocumentId;
    }
}
