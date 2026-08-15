package com.utkarsh.ai_doc_qna;

import com.utkarsh.ai_doc_qna.document.IngestionStatus;
import com.utkarsh.ai_doc_qna.document.SourceDocumentRepository;
import com.utkarsh.ai_doc_qna.qa.QaService;
import com.utkarsh.ai_doc_qna.qa.dto.AnswerResponse;
import com.utkarsh.ai_doc_qna.support.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The real thing: real OpenAI embeddings and a real chat completion, against containerised
 * Postgres and MinIO. Skipped unless {@code OPENAI_API_KEY} is set, because it costs money and
 * needs network access — {@link DocumentQaIntegrationTest} covers the same pipeline for free.
 *
 * <p>What it proves that the stubbed test cannot: that retrieval actually discriminates between
 * relevant and irrelevant content, and that the model genuinely refuses questions the corpus does
 * not cover instead of inventing an answer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "app.storage.bucket=real-e2e-test")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = "sk-.+")
class RealOpenAiEndToEndTest {

    private static final String POLICY = """
            ACME Remote Work Policy, revision 4.

            Section 1. Eligibility. All full-time employees who have completed 90 days of
            continuous service are eligible to work remotely up to three days per week.
            Contractors are not eligible under this policy.

            Section 2. Equipment. ACME provides one laptop and one external monitor to each
            remote worker. Employees may claim up to 400 USD per calendar year for a desk chair,
            reimbursed through the standard expense system.

            Section 3. Core hours. Remote employees must be reachable between 10:00 and 16:00
            in their local timezone.
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SourceDocumentRepository repository;

    @Autowired
    private QaService qaService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void ingestPolicy() throws Exception {
        repository.deleteAll();
        jdbcTemplate.update("DELETE FROM vector_store");

        String body = mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "policy.txt",
                                MediaType.TEXT_PLAIN_VALUE, POLICY.getBytes())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID documentId = UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                assertThat(repository.findById(documentId).orElseThrow().getStatus())
                        .isEqualTo(IngestionStatus.COMPLETED));
    }

    @Test
    void answersAnInCorpusQuestionAndCitesTheDocument() {
        AnswerResponse answer = qaService.ask("How many days per week can employees work remotely?");

        assertThat(answer.answerable()).isTrue();
        assertThat(answer.answer()).containsIgnoringCase("three");
        assertThat(answer.citations()).isNotEmpty();
        assertThat(answer.citations().getFirst().filename()).isEqualTo("policy.txt");
    }

    @Test
    void refusesAnOutOfCorpusQuestionInsteadOfHallucinating() {
        AnswerResponse answer = qaService.ask("Who won the 1998 FIFA World Cup?");

        assertThat(answer.answerable()).isFalse();
        assertThat(answer.citations()).isEmpty();
        assertThat(answer.answer()).doesNotContainIgnoringCase("France");
    }

    @Test
    void refusesAQuestionAboutATopicTheDocumentDoesNotCover() {
        AnswerResponse answer = qaService.ask("What is the parental leave entitlement?");

        assertThat(answer.answerable()).isFalse();
        assertThat(answer.citations()).isEmpty();
    }

    @Test
    void answersViaTheHttpEndpointWithCitations() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"What is the desk chair allowance?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answerable").value(true))
                .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("400")))
                .andExpect(jsonPath("$.data.citations[0].filename").value("policy.txt"));
    }
}
