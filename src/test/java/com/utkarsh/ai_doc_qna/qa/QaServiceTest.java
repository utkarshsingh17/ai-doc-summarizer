package com.utkarsh.ai_doc_qna.qa;

import com.utkarsh.ai_doc_qna.config.AppProperties;
import com.utkarsh.ai_doc_qna.document.ChunkMetadata;
import com.utkarsh.ai_doc_qna.document.CorpusStatus;
import com.utkarsh.ai_doc_qna.document.DocumentService;
import com.utkarsh.ai_doc_qna.qa.dto.AnswerResponse;
import com.utkarsh.ai_doc_qna.qa.dto.CitationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QaServiceTest {

    private static final UUID DOCUMENT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock
    private RetrievalService retrievalService;

    @Mock
    private GroundedAnswerGenerator answerGenerator;

    @Mock
    private DocumentService documentService;

    private QaService qaService;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties(
                null,
                null,
                new AppProperties.Qa(6, 0.45, 80));
        qaService = new QaService(retrievalService, answerGenerator, documentService, properties);
    }

    @Test
    void ask_whenNoChunksClearThreshold_shouldRefuseWithoutCallingTheModel() {
        when(retrievalService.retrieve(anyString())).thenReturn(List.of());
        when(documentService.corpusStatus()).thenReturn(new CorpusStatus(3, 0));

        AnswerResponse response = qaService.ask("Who won the 1998 World Cup?");

        assertThat(response.answerable()).isFalse();
        assertThat(response.citations()).isEmpty();
        assertThat(response.retrievedChunks()).isZero();
        // Skipping the chat call on an out-of-corpus question is the point, not an optimisation.
        verifyNoInteractions(answerGenerator);
    }

    @Test
    void ask_whenNothingHasBeenUploaded_shouldSaySoRatherThanClaimingNoMatch() {
        when(retrievalService.retrieve(anyString())).thenReturn(List.of());
        when(documentService.corpusStatus()).thenReturn(new CorpusStatus(0, 0));

        assertThat(qaService.ask("anything").answer()).contains("No documents have been uploaded");
    }

    @Test
    void ask_whileDocumentsAreStillIngesting_shouldSayTheAnswerMayNotBeReadyYet() {
        when(retrievalService.retrieve(anyString())).thenReturn(List.of());
        when(documentService.corpusStatus()).thenReturn(new CorpusStatus(2, 1));

        // Telling a user their content "is not in the documents" while it is mid-embed is wrong.
        assertThat(qaService.ask("anything").answer()).contains("still being processed");
    }

    @Test
    void ask_whenModelCitesSources_shouldMapThemBackToTheOriginalChunks() {
        when(retrievalService.retrieve(anyString())).thenReturn(List.of(
                chunk("Employees may work remotely up to three days per week.", 1, 0, 0.91),
                chunk("Contractors are not eligible under this policy.", 2, 1, 0.72)));
        when(answerGenerator.generate(anyString(), anyString()))
                .thenReturn(new GroundedAnswer("Up to three days per week.", true, List.of(1)));

        AnswerResponse response = qaService.ask("How many remote days are allowed?");

        assertThat(response.answerable()).isTrue();
        assertThat(response.retrievedChunks()).isEqualTo(2);
        assertThat(response.citations()).hasSize(1);

        CitationResponse citation = response.citations().getFirst();
        assertThat(citation.reference()).isEqualTo(1);
        assertThat(citation.documentId()).isEqualTo(DOCUMENT_ID);
        assertThat(citation.filename()).isEqualTo("policy.pdf");
        assertThat(citation.pageNumber()).isEqualTo(1);
        assertThat(citation.score()).isEqualTo(0.91);
        assertThat(citation.snippet()).contains("three days per week");
    }

    @Test
    void ask_whenModelCitesAnExcerptThatWasNotInThePrompt_shouldDropIt() {
        when(retrievalService.retrieve(anyString())).thenReturn(List.of(
                chunk("Core hours are 10:00 to 16:00.", 3, 0, 0.80)));
        // Only one excerpt was supplied, so 7 cannot be real.
        when(answerGenerator.generate(anyString(), anyString()))
                .thenReturn(new GroundedAnswer("Core hours are 10:00 to 16:00.", true, List.of(1, 7)));

        AnswerResponse response = qaService.ask("What are core hours?");

        assertThat(response.citations()).hasSize(1);
        assertThat(response.citations().getFirst().reference()).isEqualTo(1);
    }

    @Test
    void ask_whenModelReportsUnanswerable_shouldReturnNoCitations() {
        when(retrievalService.retrieve(anyString())).thenReturn(List.of(
                chunk("Equipment budget is 400 USD.", 2, 0, 0.55)));
        when(answerGenerator.generate(anyString(), anyString()))
                .thenReturn(new GroundedAnswer("The documents do not cover parental leave.", false, List.of()));

        AnswerResponse response = qaService.ask("What is the parental leave policy?");

        assertThat(response.answerable()).isFalse();
        assertThat(response.citations()).isEmpty();
        // The chunks were retrieved but did not answer the question — distinct from "no match".
        assertThat(response.retrievedChunks()).isEqualTo(1);
    }

    @Test
    void ask_shouldNumberExcerptsFromOneAndIncludeSourceLabels() {
        when(retrievalService.retrieve(anyString())).thenReturn(List.of(
                chunk("First excerpt.", 4, 0, 0.9),
                chunk("Second excerpt.", 5, 1, 0.8)));
        when(answerGenerator.generate(anyString(), anyString()))
                .thenReturn(new GroundedAnswer("ok", true, List.of(1)));

        qaService.ask("anything");

        var context = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(answerGenerator).generate(anyString(), context.capture());
        assertThat(context.getValue())
                .contains("[1] source: policy.pdf, page 4")
                .contains("[2] source: policy.pdf, page 5")
                .doesNotContain("[0]");
    }

    @Test
    void ask_shouldTruncateLongSnippetsToTheConfiguredLimit() {
        String longText = "word ".repeat(200);
        when(retrievalService.retrieve(anyString())).thenReturn(List.of(chunk(longText, 1, 0, 0.9)));
        when(answerGenerator.generate(anyString(), anyString()))
                .thenReturn(new GroundedAnswer("ok", true, List.of(1)));

        AnswerResponse response = qaService.ask("anything");

        // 80-char limit from the test properties, plus the ellipsis.
        assertThat(response.citations().getFirst().snippet()).hasSize(81).endsWith("…");
    }

    @Test
    void ask_whenModelCitesTheSameExcerptTwice_shouldReturnOneCitation() {
        when(retrievalService.retrieve(anyString())).thenReturn(List.of(chunk("Some text.", 1, 0, 0.9)));
        when(answerGenerator.generate(anyString(), anyString()))
                .thenReturn(new GroundedAnswer("ok", true, List.of(1, 1)));

        assertThat(qaService.ask("anything").citations()).hasSize(1);
    }

    private static Document chunk(String text, int page, int chunkIndex, double score) {
        return Document.builder()
                .id(UUID.randomUUID().toString())
                .text(text)
                .metadata(Map.of(
                        ChunkMetadata.DOCUMENT_ID, DOCUMENT_ID.toString(),
                        ChunkMetadata.FILENAME, "policy.pdf",
                        ChunkMetadata.PAGE_NUMBER, page,
                        ChunkMetadata.CHUNK_INDEX, chunkIndex))
                .score(score)
                .build();
    }
}
