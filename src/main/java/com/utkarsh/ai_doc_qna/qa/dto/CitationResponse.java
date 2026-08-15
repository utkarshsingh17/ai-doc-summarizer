package com.utkarsh.ai_doc_qna.qa.dto;

import java.util.UUID;

/**
 * A source the answer actually drew on.
 *
 * @param reference  the excerpt number the model cited, so the answer can be traced back
 * @param pageNumber 1-based page for PDFs; null for plain text, which has no pages
 * @param snippet    the chunk text (truncated), so the user can verify the claim without
 *                   reopening the original file
 * @param score      similarity of this chunk to the question, 0..1
 */
public record CitationResponse(
        int reference,
        UUID documentId,
        String filename,
        Integer pageNumber,
        int chunkIndex,
        String snippet,
        Double score) {
}
