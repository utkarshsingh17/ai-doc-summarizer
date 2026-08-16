package com.utkarsh.ai_doc_qna.document;

import com.utkarsh.ai_doc_qna.auth.UserPrincipal;
import com.utkarsh.ai_doc_qna.common.ApiResponse;
import com.utkarsh.ai_doc_qna.document.dto.DocumentPageResponse;
import com.utkarsh.ai_doc_qna.document.dto.DocumentResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Every endpoint here is scoped to the authenticated caller — a user only ever sees, uploads to,
 * or deletes their own documents.
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }


    /**
     * Returns 202-style semantics under a 201: the document exists, but is only answerable once
     * its status reaches {@code COMPLETED}.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<DocumentResponse>> upload(@RequestParam("file") MultipartFile file,
                                                                 @AuthenticationPrincipal UserPrincipal principal) {
        DocumentResponse response = DocumentResponse.from(documentService.upload(file, principal.getId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @GetMapping
    public ApiResponse<DocumentPageResponse> list(
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(DocumentPageResponse.from(
                documentService.list(pageable, principal.getId()).map(DocumentResponse::from)));
    }

    @GetMapping("/{id}")
    public ApiResponse<DocumentResponse> get(@PathVariable UUID id, @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(DocumentResponse.from(documentService.get(id, principal.getId())));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal UserPrincipal principal) {
        documentService.delete(id, principal.getId());
        return ResponseEntity.noContent().build();
    }
}
