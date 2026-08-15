package com.utkarsh.ai_doc_qna.document;

import com.utkarsh.ai_doc_qna.common.exception.DocumentNotFoundException;
import com.utkarsh.ai_doc_qna.common.exception.DuplicateDocumentException;
import com.utkarsh.ai_doc_qna.common.exception.UnsupportedFileTypeException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DocumentController.class)
class DocumentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentService documentService;

    @Test
    void upload_withValidFile_shouldReturn201AndPendingStatus() throws Exception {
        when(documentService.upload(any())).thenReturn(
                SourceDocument.create("policy.pdf", "application/pdf", 1024, "abc", "documents/x/policy.pdf"));

        mockMvc.perform(multipart("/api/v1/documents").file(pdf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.filename").value("policy.pdf"))
                // The document exists but is not answerable until ingestion completes.
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.chunkCount").value(0));
    }

    @Test
    void upload_withDuplicateFile_shouldReturn409() throws Exception {
        UUID existingId = UUID.randomUUID();
        when(documentService.upload(any()))
                .thenThrow(new DuplicateDocumentException(existingId, "policy.pdf"));

        mockMvc.perform(multipart("/api/v1/documents").file(pdf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_DOCUMENT"))
                .andExpect(jsonPath("$.error.details[0]").value("existingDocumentId=" + existingId));
    }

    @Test
    void upload_withUnsupportedType_shouldReturn415() throws Exception {
        when(documentService.upload(any()))
                .thenThrow(new UnsupportedFileTypeException("application/zip", Set.of("application/pdf")));

        mockMvc.perform(multipart("/api/v1/documents").file(pdf()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_FILE_TYPE"));
    }

    @Test
    void list_shouldReturnPaginationEnvelopeRatherThanRawSpringDataPage() throws Exception {
        Page<SourceDocument> page = new PageImpl<>(
                List.of(SourceDocument.create("a.pdf", "application/pdf", 1, "c1", "k1")),
                PageRequest.of(0, 20), 1);
        when(documentService.list(any())).thenReturn(page);

        mockMvc.perform(get("/api/v1/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].filename").value("a.pdf"))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.last").value(true))
                // Spring Data internals must not leak into the response.
                .andExpect(jsonPath("$.data.pageable").doesNotExist());
    }

    @Test
    void get_whenIdIsUnknown_shouldReturn404() throws Exception {
        UUID id = UUID.randomUUID();
        when(documentService.get(id)).thenThrow(new DocumentNotFoundException(id));

        mockMvc.perform(get("/api/v1/documents/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    void delete_shouldReturn204WithNoBody() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/documents/{id}", id))
                .andExpect(status().isNoContent());

        verify(documentService).delete(id);
    }

    @Test
    void delete_whenIdIsUnknown_shouldReturn404() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new DocumentNotFoundException(id)).when(documentService).delete(id);

        mockMvc.perform(delete("/api/v1/documents/{id}", id))
                .andExpect(status().isNotFound());
    }

    private static MockMultipartFile pdf() {
        return new MockMultipartFile("file", "policy.pdf", "application/pdf", "%PDF-1.4".getBytes());
    }
}
