package com.utkarsh.ai_doc_qna;

import com.utkarsh.ai_doc_qna.document.IngestionStatus;
import com.utkarsh.ai_doc_qna.document.SourceDocumentRepository;
import com.utkarsh.ai_doc_qna.qa.QaService;
import com.utkarsh.ai_doc_qna.qa.dto.AnswerResponse;
import com.utkarsh.ai_doc_qna.qa.dto.CitationResponse;
import com.utkarsh.ai_doc_qna.support.StubAiConfiguration;
import com.utkarsh.ai_doc_qna.support.TestcontainersConfiguration;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the PDF path specifically: page numbers only exist because
 * {@code PagePdfDocumentReader} stamps them, and they are what makes a citation point at a
 * section rather than at a whole file.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, StubAiConfiguration.class})
@TestPropertySource(properties = {
        "spring.ai.openai.api-key=not-used-by-the-stub",
        "app.storage.bucket=pdf-test"
})
class PdfCitationIntegrationTest {

    private static final List<String> PAGES = List.of(
            "Section 1 Eligibility. Full time employees may work remotely up to three days each week.",
            "Section 2 Equipment. The company reimburses up to 400 USD for a desk chair each year.",
            "Section 3 Core hours. Remote employees stay reachable between 10:00 and 16:00 daily.");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SourceDocumentRepository repository;

    @Autowired
    private QaService qaService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StubAiConfiguration.StubChatResponses chatResponses;

    @BeforeEach
    void resetCorpus() {
        repository.deleteAll();
        jdbcTemplate.update("DELETE FROM vector_store");
    }

    @Test
    void pdfChunksCarryPageNumbersThroughToCitations() throws Exception {
        UUID documentId = uploadPdf();

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                assertThat(repository.findById(documentId).orElseThrow().getStatus())
                        .isEqualTo(IngestionStatus.COMPLETED));

        // One chunk per page, since each page is well under the chunk size.
        assertThat(repository.findById(documentId).orElseThrow().getChunkCount()).isEqualTo(PAGES.size());

        chatResponses.reply("Up to three days each week.", true, "1,2,3");
        AnswerResponse answer = qaService.ask("What does the policy say?");

        assertThat(answer.citations()).hasSize(3);
        assertThat(answer.citations()).allSatisfy(citation -> {
            assertThat(citation.filename()).isEqualTo("handbook.pdf");
            assertThat(citation.documentId()).isEqualTo(documentId);
            assertThat(citation.pageNumber()).isNotNull();
        });
        // Every page of the source is represented, and page numbers are 1-based.
        assertThat(answer.citations().stream().map(CitationResponse::pageNumber))
                .containsExactlyInAnyOrder(1, 2, 3);
    }

    @Test
    void pdfWithNoExtractableTextIsMarkedFailedRatherThanSilentlyEmpty() throws Exception {
        // A PDF with pages but no text content — the shape a scanned document takes.
        byte[] blank;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(out);
            blank = out.toByteArray();
        }

        UUID documentId = upload(new MockMultipartFile("file", "scanned.pdf", "application/pdf", blank));

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                assertThat(repository.findById(documentId).orElseThrow().getStatus())
                        .isEqualTo(IngestionStatus.FAILED));

        var failed = repository.findById(documentId).orElseThrow();
        assertThat(failed.getErrorMessage()).contains("OCR");
        assertThat(failed.isAnswerable()).isFalse();
    }

    private UUID uploadPdf() throws Exception {
        return upload(new MockMultipartFile("file", "handbook.pdf", "application/pdf", buildPdf()));
    }

    private UUID upload(MockMultipartFile file) throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/documents").file(file))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
    }

    /** Builds a real multi-page PDF so the reader is exercised, not mocked. */
    private static byte[] buildPdf() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : PAGES) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(50, 700);
                    content.showText(text);
                    content.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        }
    }
}
