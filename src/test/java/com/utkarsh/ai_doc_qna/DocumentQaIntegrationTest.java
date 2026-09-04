package com.utkarsh.ai_doc_qna;

import com.utkarsh.ai_doc_qna.auth.UserRepository;
import com.utkarsh.ai_doc_qna.document.IngestionStatus;
import com.utkarsh.ai_doc_qna.document.SourceDocument;
import com.utkarsh.ai_doc_qna.document.SourceDocumentRepository;
import com.utkarsh.ai_doc_qna.qa.QaService;
import com.utkarsh.ai_doc_qna.qa.dto.AnswerResponse;
import com.utkarsh.ai_doc_qna.qa.dto.CitationResponse;
import com.utkarsh.ai_doc_qna.support.AuthTestSupport;
import com.utkarsh.ai_doc_qna.support.StubAiConfiguration;
import com.utkarsh.ai_doc_qna.support.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full pipeline against a real Qdrant, with the AI models stubbed. Runs without an API key and
 * costs nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, StubAiConfiguration.class})
// The stub models replace these beans, but OpenAI autoconfiguration still wants a key.
@TestPropertySource(properties = "spring.ai.openai.api-key=not-used-by-the-stub")
class DocumentQaIntegrationTest {

    private static final String POLICY = """
            ACME Remote Work Policy, revision 4.

            Section 1. Eligibility. All full-time employees who have completed 90 days of
            continuous service are eligible to work remotely up to three days per week.
            Contractors are not eligible under this policy.

            Section 2. Equipment. ACME provides one laptop and one external monitor to each
            remote worker. Employees may claim up to 400 USD per calendar year for a desk chair.
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SourceDocumentRepository repository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private QaService qaService;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private StubAiConfiguration.StubChatResponses chatResponses;

    private AuthTestSupport.Registered user;

    /**
     * No vector-store wipe here: every test uploads its own document under a fresh random
     * document id and a fresh registered user, so nothing from an earlier test can be retrieved
     * by, or counted against, this one — the same isolation the app itself relies on in
     * production. Only the relational data needs a manual reset.
     */
    @BeforeEach
    void resetCorpus() throws Exception {
        repository.deleteAll();
        user = AuthTestSupport.register(mockMvc, userRepository);
    }

    @Test
    void uploadedDocumentBecomesAnswerableAndProducesVerifiableCitations() throws Exception {
        UUID documentId = uploadPolicy();
        awaitCompletion(documentId);

        SourceDocument ingested = repository.findById(documentId).orElseThrow();
        assertThat(ingested.getChunkCount()).isPositive();
        assertThat(ingested.isAnswerable()).isTrue();
        assertThat(countChunks(documentId)).isEqualTo(ingested.getChunkCount());

        chatResponses.reply("Up to three days per week.", true, "1");
        AnswerResponse answer = qaService.ask("How many remote days are allowed?", user.userId());

        assertThat(answer.answerable()).isTrue();
        assertThat(answer.retrievedChunks()).isPositive();
        assertThat(answer.citations()).hasSize(1);

        CitationResponse citation = answer.citations().getFirst();
        assertThat(citation.documentId()).isEqualTo(documentId);
        assertThat(citation.filename()).isEqualTo("policy.txt");
        assertThat(citation.snippet()).isNotBlank();
        // The cited snippet must be real text from the document, not something invented.
        assertThat(POLICY.replaceAll("\\s+", " ")).contains(citation.snippet().replace("…", "").trim());

        // The prompt must actually carry the excerpts the citation refers to.
        assertThat(chatResponses.lastPrompt()).contains("[1] source: policy.txt");
    }

    @Test
    void modelReportingUnanswerableProducesNoCitations() throws Exception {
        awaitCompletion(uploadPolicy());

        chatResponses.reply("The documents do not mention parental leave.", false, "");
        AnswerResponse answer = qaService.ask("What is the parental leave policy?", user.userId());

        assertThat(answer.answerable()).isFalse();
        assertThat(answer.citations()).isEmpty();
    }

    @Test
    void deletingADocumentRemovesItsChunksFromTheVectorStore() throws Exception {
        UUID documentId = uploadPolicy();
        awaitCompletion(documentId);
        assertThat(countChunks(documentId)).isPositive();

        mockMvc.perform(delete("/api/v1/documents/{id}", documentId).cookie(user.accessCookie()))
                .andExpect(status().isNoContent());

        assertThat(repository.findById(documentId)).isEmpty();
        // Chunks must go with the document, or citations would point at a document that is gone.
        assertThat(countChunks(documentId)).isZero();
    }

    /**
     * "Notes" are a first-class input, and a short note is well under the splitter's default
     * minimum chunk size. It must still ingest rather than being reported as unreadable.
     */
    @Test
    void aShortNoteStillProducesAnIngestibleChunk() throws Exception {
        UUID documentId = upload(new MockMultipartFile("file", "note.txt",
                MediaType.TEXT_PLAIN_VALUE, "The wifi password is hunter2.".getBytes()));
        awaitCompletion(documentId);

        SourceDocument note = repository.findById(documentId).orElseThrow();
        assertThat(note.getChunkCount()).isEqualTo(1);
        assertThat(note.isAnswerable()).isTrue();
        assertThat(note.getErrorMessage()).isNull();

        chatResponses.reply("hunter2", true, "1");
        AnswerResponse answer = qaService.ask("What is the wifi password?", user.userId());
        assertThat(answer.citations()).hasSize(1);
        assertThat(answer.citations().getFirst().snippet()).contains("hunter2");
    }

    @Test
    void reuploadingTheSameContentIsRejected() throws Exception {
        awaitCompletion(uploadPolicy());

        mockMvc.perform(multipart("/api/v1/documents").file(policyFile())
                        .cookie(user.accessCookie()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_DOCUMENT"));
    }

    private UUID uploadPolicy() throws Exception {
        return upload(policyFile());
    }

    private UUID upload(MockMultipartFile file) throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/documents").file(file)
                        .cookie(user.accessCookie()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
    }

    /** Ingestion is asynchronous, so the test waits for the status the pipeline writes. */
    private void awaitCompletion(UUID documentId) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                assertThat(repository.findById(documentId).orElseThrow().getStatus())
                        .isEqualTo(IngestionStatus.COMPLETED));
    }

    /** Mirrors {@code RetrievalService.retrieveForDocument}'s filter, scoped to one document. */
    private int countChunks(UUID documentId) {
        return vectorStore.similaritySearch(SearchRequest.builder()
                        .query("count")
                        .topK(1000)
                        .similarityThreshold(0)
                        .filterExpression("document_id == '%s'".formatted(documentId))
                        .build())
                .size();
    }

    private static MockMultipartFile policyFile() {
        return new MockMultipartFile("file", "policy.txt",
                MediaType.TEXT_PLAIN_VALUE, POLICY.getBytes());
    }
}
