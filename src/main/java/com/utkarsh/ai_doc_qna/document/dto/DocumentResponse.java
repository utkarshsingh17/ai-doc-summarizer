package com.utkarsh.ai_doc_qna.document.dto;

import com.utkarsh.ai_doc_qna.document.IngestionStatus;
import com.utkarsh.ai_doc_qna.document.SourceDocument;

import java.time.Instant;
import java.util.UUID;

public record DocumentResponse(
        UUID id,
        String filename,
        String contentType,
        long sizeBytes,
        IngestionStatus status,
        int chunkCount,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt) {

    public static DocumentResponse from(SourceDocument document) {
        return new DocumentResponse(
                document.getId(),
                document.getFilename(),
                document.getContentType(),
                document.getSizeBytes(),
                document.getStatus(),
                document.getChunkCount(),
                document.getErrorMessage(),
                document.getCreatedAt(),
                document.getUpdatedAt());
    }
}
