package com.utkarsh.ai_doc_qna.document;

import com.utkarsh.ai_doc_qna.auth.JwtService;
import com.utkarsh.ai_doc_qna.auth.UserPrincipal;
import com.utkarsh.ai_doc_qna.common.exception.DocumentNotFoundException;
import com.utkarsh.ai_doc_qna.common.exception.DuplicateDocumentException;
import com.utkarsh.ai_doc_qna.common.exception.UnsupportedFileTypeException;
import com.utkarsh.ai_doc_qna.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code SecurityMockMvcRequestPostProcessors.user(...)} stashes the principal in the mock
 * session and relies on the security filter chain running to load it back into
 * {@code SecurityContextHolder} — so filters must stay enabled here, not disabled.
 * {@code SecurityConfig} is imported explicitly: {@code @WebMvcTest}'s restricted component scan
 * does not pick it up on its own, and without its {@code @EnableWebSecurity} being processed,
 * {@code @AuthenticationPrincipal} has no argument resolver registered — parameters silently fall
 * back to Spring MVC's data-binder, which (mis)resolves {@code UserPrincipal principal} from
 * whatever request value happens to share a constructor-parameter name, such as the {@code id}
 * path variable. {@code JwtAuthenticationFilter}'s constructor dependencies are mocked purely so
 * the context can start; this slice never exercises the JWT path itself.
 */
@WebMvcTest(DocumentController.class)
@Import(SecurityConfig.class)
class DocumentControllerTest {

    private static final UUID OWNER_ID = UUID.fromString("99999999-8888-7777-6666-555555555555");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentService documentService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @Test
    void upload_withValidFile_shouldReturn201AndPendingStatus() throws Exception {
        when(documentService.upload(any(), eq(OWNER_ID))).thenReturn(
                SourceDocument.create("policy.pdf", "application/pdf", 1024, "abc", "documents/x/policy.pdf", OWNER_ID));

        mockMvc.perform(multipart("/api/v1/documents").file(pdf()).with(auth()))
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
        when(documentService.upload(any(), eq(OWNER_ID)))
                .thenThrow(new DuplicateDocumentException(existingId, "policy.pdf"));

        mockMvc.perform(multipart("/api/v1/documents").file(pdf()).with(auth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_DOCUMENT"))
                .andExpect(jsonPath("$.error.details[0]").value("existingDocumentId=" + existingId));
    }

    @Test
    void upload_withUnsupportedType_shouldReturn415() throws Exception {
        when(documentService.upload(any(), eq(OWNER_ID)))
                .thenThrow(new UnsupportedFileTypeException("application/zip", Set.of("application/pdf")));

        mockMvc.perform(multipart("/api/v1/documents").file(pdf()).with(auth()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_FILE_TYPE"));
    }

    @Test
    void list_shouldReturnPaginationEnvelopeRatherThanRawSpringDataPage() throws Exception {
        Page<SourceDocument> page = new PageImpl<>(
                List.of(SourceDocument.create("a.pdf", "application/pdf", 1, "c1", "k1", OWNER_ID)),
                PageRequest.of(0, 20), 1);
        when(documentService.list(any(), eq(OWNER_ID))).thenReturn(page);

        mockMvc.perform(get("/api/v1/documents").with(auth()))
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
        when(documentService.get(id, OWNER_ID)).thenThrow(new DocumentNotFoundException(id));

        mockMvc.perform(get("/api/v1/documents/{id}", id).with(auth()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    void delete_shouldReturn204WithNoBody() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/documents/{id}", id).with(auth()))
                .andExpect(status().isNoContent());

        verify(documentService).delete(id, OWNER_ID);
    }

    @Test
    void delete_whenIdIsUnknown_shouldReturn404() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new DocumentNotFoundException(id)).when(documentService).delete(id, OWNER_ID);

        mockMvc.perform(delete("/api/v1/documents/{id}", id).with(auth()))
                .andExpect(status().isNotFound());
    }

    private static RequestPostProcessor auth() {
        return SecurityMockMvcRequestPostProcessors.user(
                new UserPrincipal(OWNER_ID, "owner@example.com", "irrelevant-hash"));
    }

    private static MockMultipartFile pdf() {
        return new MockMultipartFile("file", "policy.pdf", "application/pdf", "%PDF-1.4".getBytes());
    }
}
