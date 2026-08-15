package com.utkarsh.ai_doc_qna.document;

public enum IngestionStatus {

    /** Stored in object storage, not yet parsed or embedded. */
    PENDING,

    /** Being parsed, split and embedded. */
    PROCESSING,

    /** Chunks are in the vector store and the document is answerable. */
    COMPLETED,

    /** Ingestion failed; see {@code errorMessage}. The original file is still stored. */
    FAILED
}
