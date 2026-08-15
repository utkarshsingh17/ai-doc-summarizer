package com.utkarsh.ai_doc_qna.qa.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AskQuestionRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = 2000, message = "must be at most 2000 characters")
        String question) {
}
