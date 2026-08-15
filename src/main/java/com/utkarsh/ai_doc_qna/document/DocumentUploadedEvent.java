package com.utkarsh.ai_doc_qna.document;

import java.util.UUID;

/**
 * Published when an upload is persisted. Consumed after commit so the ingestion worker is
 * guaranteed to see the row.
 */
public record DocumentUploadedEvent(UUID documentId) {
}
