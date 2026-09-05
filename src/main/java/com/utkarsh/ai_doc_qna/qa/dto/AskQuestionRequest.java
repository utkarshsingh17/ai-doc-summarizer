package com.utkarsh.ai_doc_qna.qa.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * {@code documentIds} is optional and only meaningful on {@code POST /questions}: null or empty
 * means the whole corpus, same as omitting it entirely. {@code POST /documents/{id}/questions}
 * ignores it — that endpoint's scope comes from the path variable.
 */
public record AskQuestionRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = 2000, message = "must be at most 2000 characters")
        String question,
        List<UUID> documentIds) {
}
