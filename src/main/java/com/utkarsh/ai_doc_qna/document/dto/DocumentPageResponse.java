package com.utkarsh.ai_doc_qna.document.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Explicit pagination shape. Serializing a {@code Page} directly leaks Spring Data internals and
 * is unstable across versions.
 */
public record DocumentPageResponse(
        List<DocumentResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last) {

    public static DocumentPageResponse from(Page<DocumentResponse> page) {
        return new DocumentPageResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast());
    }
}
