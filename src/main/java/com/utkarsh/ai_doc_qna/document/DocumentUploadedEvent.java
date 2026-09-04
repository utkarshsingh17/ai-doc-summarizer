package com.utkarsh.ai_doc_qna.document;

import java.util.UUID;

/**
 * Published once a document's metadata and content are upserted into Qdrant. Unlike the old
 * JPA-backed version of this event, there is no transaction commit to wait for — the upsert
 * itself is synchronous and immediately visible, so the listener can run as soon as it fires.
 */
public record DocumentUploadedEvent(UUID documentId) {
}
