package com.utkarsh.ai_doc_qna.qa;

import com.utkarsh.ai_doc_qna.common.exception.AiServiceException;
import com.utkarsh.ai_doc_qna.qa.dto.AnswerResponse;
import com.utkarsh.ai_doc_qna.qa.dto.CitationResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(QaController.class)
class QaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QaService qaService;

    @Test
    void ask_withGroundedAnswer_shouldReturnAnswerAndCitations() throws Exception {
        UUID documentId = UUID.randomUUID();
        when(qaService.ask(anyString())).thenReturn(new AnswerResponse(
                "How many remote days?",
                "Up to three days per week.",
                true,
                List.of(new CitationResponse(1, documentId, "policy.pdf", 2, 0, "…three days…", 0.88)),
                3));

        mockMvc.perform(post("/api/v1/questions")
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
        when(qaService.ask(anyString())).thenReturn(new AnswerResponse(
                "Who won the 1998 World Cup?",
                "I could not find anything about that in the uploaded documents.",
                false, List.of(), 0));

        mockMvc.perform(post("/api/v1/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Who won the 1998 World Cup?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answerable").value(false))
                .andExpect(jsonPath("$.data.citations").isEmpty());
    }

    @Test
    void ask_withBlankQuestion_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void ask_whenProviderIsUnreachable_shouldReturn503() throws Exception {
        when(qaService.ask(anyString()))
                .thenThrow(new AiServiceException("provider down", null, true));

        mockMvc.perform(post("/api/v1/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"anything\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("AI_UNAVAILABLE"));
    }

    @Test
    void ask_whenProviderRejectsTheRequest_shouldReturn502() throws Exception {
        when(qaService.ask(anyString()))
                .thenThrow(new AiServiceException("bad key", null, false));

        mockMvc.perform(post("/api/v1/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"anything\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("AI_REQUEST_FAILED"));
    }
}
