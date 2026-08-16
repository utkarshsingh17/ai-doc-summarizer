package com.utkarsh.ai_doc_qna.qa;

import com.utkarsh.ai_doc_qna.auth.JwtService;
import com.utkarsh.ai_doc_qna.auth.UserPrincipal;
import com.utkarsh.ai_doc_qna.common.exception.AiServiceException;
import com.utkarsh.ai_doc_qna.common.exception.DocumentNotFoundException;
import com.utkarsh.ai_doc_qna.config.AppProperties;
import com.utkarsh.ai_doc_qna.config.SecurityConfig;
import com.utkarsh.ai_doc_qna.qa.dto.AnswerResponse;
import com.utkarsh.ai_doc_qna.qa.dto.CitationResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * See {@code DocumentControllerTest} for why filters stay enabled and {@code SecurityConfig} is
 * imported explicitly, despite the principal being injected via a request post-processor rather
 * than a real JWT.
 */
@WebMvcTest(QaController.class)
@Import(SecurityConfig.class)
@EnableConfigurationProperties(AppProperties.class)
class QaControllerTest {

    private static final UUID OWNER_ID = UUID.fromString("99999999-8888-7777-6666-555555555555");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QaService qaService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @Test
    void ask_withGroundedAnswer_shouldReturnAnswerAndCitations() throws Exception {
        UUID documentId = UUID.randomUUID();
        when(qaService.ask(anyString(), eq(OWNER_ID))).thenReturn(new AnswerResponse(
                "How many remote days?",
                "Up to three days per week.",
                true,
                List.of(new CitationResponse(1, documentId, "policy.pdf", 2, 0, "…three days…", 0.88)),
                3));

        mockMvc.perform(post("/api/v1/questions")
                        .with(auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"How many remote days?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.answerable").value(true))
                .andExpect(jsonPath("$.data.answer").value("Up to three days per week."))
                .andExpect(jsonPath("$.data.retrievedChunks").value(3))
                .andExpect(jsonPath("$.data.citations[0].reference").value(1))
                .andExpect(jsonPath("$.data.citations[0].filename").value("policy.pdf"))
                .andExpect(jsonPath("$.data.citations[0].pageNumber").value(2))
                .andExpect(jsonPath("$.data.citations[0].documentId").value(documentId.toString()));
    }

    @Test
    void ask_whenCorpusDoesNotCoverTheQuestion_shouldReturnUnanswerableWithNoCitations() throws Exception {
        when(qaService.ask(anyString(), eq(OWNER_ID))).thenReturn(new AnswerResponse(
                "Who won the 1998 World Cup?",
                "I could not find anything about that in the uploaded documents.",
                false, List.of(), 0));

        mockMvc.perform(post("/api/v1/questions")
                        .with(auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Who won the 1998 World Cup?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answerable").value(false))
                .andExpect(jsonPath("$.data.citations").isEmpty());
    }

    @Test
    void ask_withBlankQuestion_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/questions")
                        .with(auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void ask_whenProviderIsUnreachable_shouldReturn503() throws Exception {
        when(qaService.ask(anyString(), eq(OWNER_ID)))
                .thenThrow(new AiServiceException("provider down", null, true));

        mockMvc.perform(post("/api/v1/questions")
                        .with(auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"anything\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("AI_UNAVAILABLE"));
    }

    @Test
    void ask_whenProviderRejectsTheRequest_shouldReturn502() throws Exception {
        when(qaService.ask(anyString(), eq(OWNER_ID)))
                .thenThrow(new AiServiceException("bad key", null, false));

        mockMvc.perform(post("/api/v1/questions")
                        .with(auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"anything\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("AI_REQUEST_FAILED"));
    }

    @Test
    void askAboutDocument_withGroundedAnswer_shouldReturnAnswerScopedToThatDocument() throws Exception {
        UUID documentId = UUID.randomUUID();
        when(qaService.askAboutDocument(anyString(), eq(documentId), eq(OWNER_ID))).thenReturn(new AnswerResponse(
                "What are core hours?",
                "10:00 to 16:00.",
                true,
                List.of(new CitationResponse(1, documentId, "handbook.pdf", 3, 0, "…10:00 to 16:00…", 0.9)),
                1));

        mockMvc.perform(post("/api/v1/documents/{documentId}/questions", documentId)
                        .with(auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"What are core hours?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answerable").value(true))
                .andExpect(jsonPath("$.data.citations[0].documentId").value(documentId.toString()));
    }

    @Test
    void askAboutDocument_whenDocumentIsNotOwnedByCaller_shouldReturn404() throws Exception {
        UUID documentId = UUID.randomUUID();
        when(qaService.askAboutDocument(anyString(), eq(documentId), eq(OWNER_ID)))
                .thenThrow(new DocumentNotFoundException(documentId));

        mockMvc.perform(post("/api/v1/documents/{documentId}/questions", documentId)
                        .with(auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"anything\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_NOT_FOUND"));
    }

    /**
     * A framework-level exception ({@code HttpRequestMethodNotSupportedException}) previously got
     * swallowed by the catch-all {@code Exception} handler and surfaced as a misleading 500.
     */
    @Test
    void ask_withWrongHttpMethod_shouldReturn405NotInternalError() throws Exception {
        mockMvc.perform(get("/api/v1/questions").with(auth()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
    }

    /** Same swallowed-exception class as the wrong-method case, for a malformed body instead. */
    @Test
    void ask_withMalformedJsonBody_shouldReturn400NotInternalError() throws Exception {
        mockMvc.perform(post("/api/v1/questions")
                        .with(auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": not valid json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST_BODY"));
    }

    private static RequestPostProcessor auth() {
        return SecurityMockMvcRequestPostProcessors.user(
                new UserPrincipal(OWNER_ID, "owner@example.com", "irrelevant-hash"));
    }
}
