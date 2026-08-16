package com.utkarsh.ai_doc_qna.qa;

import com.utkarsh.ai_doc_qna.config.AppProperties;
import com.utkarsh.ai_doc_qna.document.ChunkMetadata;
import com.utkarsh.ai_doc_qna.document.CorpusStatus;
import com.utkarsh.ai_doc_qna.document.DocumentService;
import com.utkarsh.ai_doc_qna.qa.dto.AnswerResponse;
import com.utkarsh.ai_doc_qna.qa.dto.CitationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Answers a question using only the uploaded corpus, and reports which excerpts it used.
 *
 * <p>The flow is deliberately explicit rather than advisor-driven:
 * retrieve → number the excerpts → ask for structured output → map the cited numbers back to the
 * chunks they came from. That last step is what turns "the model said so" into a citation the
 * user can check.
 */
@Service
public class QaService {

    private static final Logger log = LoggerFactory.getLogger(QaService.class);

    private static final String NO_DOCUMENTS_MESSAGE =
            "No documents have been uploaded yet, so there is nothing to answer from.";

    private static final String STILL_INGESTING_MESSAGE =
            "I could not find anything about that. Some documents are still being processed — "
                    + "try again in a moment.";

    private static final String NO_MATCH_MESSAGE =
            "I could not find anything about that in the uploaded documents.";

    private final RetrievalService retrievalService;
    private final GroundedAnswerGenerator answerGenerator;
    private final DocumentService documentService;
    private final AppProperties properties;

    public QaService(RetrievalService retrievalService,
                     GroundedAnswerGenerator answerGenerator,
                     DocumentService documentService,
                     AppProperties properties) {
        this.retrievalService = retrievalService;
        this.answerGenerator = answerGenerator;
        this.documentService = documentService;
        this.properties = properties;
    }


    public AnswerResponse ask(String question, UUID ownerId) {
        List<Document> chunks = retrievalService.retrieve(question, ownerId);
        return answer(question, chunks, () -> noMatchMessage(ownerId));
    }

    /**
     * Same grounded-answer flow as {@link #ask}, narrowed to one document. {@code documentId}
     * must belong to {@code ownerId} — {@link DocumentService#get} throws
     * {@link com.utkarsh.ai_doc_qna.common.exception.DocumentNotFoundException} otherwise, which
     * is also the correct response for "exists but is someone else's": existence is not leaked.
     */
    public AnswerResponse askAboutDocument(String question, UUID documentId, UUID ownerId) {
        documentService.get(documentId, ownerId);
        List<Document> chunks = retrievalService.retrieveForDocument(question, documentId);
        return answer(question, chunks, () -> NO_MATCH_MESSAGE);
    }

    private AnswerResponse answer(String question, List<Document> chunks, Supplier<String> noMatchMessage) {
        // Nothing cleared the similarity floor, so there is nothing to ground an answer in.
        // Refusing here is both the correct answer and one saved chat call.
        if (chunks.isEmpty()) {
            log.debug("No chunks above threshold; refusing without a chat call");
            return new AnswerResponse(question, noMatchMessage.get(), false, List.of(), 0);
        }

        GroundedAnswer generated = answerGenerator.generate(question, buildNumberedContext(chunks));

        if (!generated.answerable()) {
            return new AnswerResponse(question, generated.answer(), false, List.of(), chunks.size());
        }
        return new AnswerResponse(question, generated.answer(), true,
                toCitations(generated.usedSources(), chunks), chunks.size());
    }

    /**
     * "Not in your documents" is the wrong thing to say when nothing has been uploaded yet, or
     * when the answer is sitting in a file that is still being embedded.
     */
    private String noMatchMessage(UUID ownerId) {
        CorpusStatus corpus = documentService.corpusStatus(ownerId);
        if (corpus.isEmpty()) {
            return NO_DOCUMENTS_MESSAGE;
        }
        if (corpus.hasWorkInProgress()) {
            return STILL_INGESTING_MESSAGE;
        }
        return NO_MATCH_MESSAGE;
    }

    /**
     * Numbers the excerpts so the model can refer to them by index. Numbering is 1-based because
     * models cite "[1]" far more reliably than "[0]".
     */
    private String buildNumberedContext(List<Document> chunks) {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            Map<String, Object> metadata = chunk.getMetadata();
            context.append("[").append(i + 1).append("] ")
                    .append("source: ").append(metadata.get(ChunkMetadata.FILENAME));
            Integer page = pageNumber(metadata);
            if (page != null) {
                context.append(", page ").append(page);
            }
            context.append('\n')
                    .append(chunk.getText())
                    .append("\n\n");
        }
        return context.toString();
    }

    /**
     * Maps the excerpt numbers the model reported back onto the chunks they came from.
     * Out-of-range numbers are dropped rather than trusted — a hallucinated citation is worse
     * than a missing one.
     */
    private List<CitationResponse> toCitations(List<Integer> usedSources, List<Document> chunks) {
        List<CitationResponse> citations = new ArrayList<>();
        for (Integer reference : new LinkedHashSet<>(usedSources)) {
            if (reference == null || reference < 1 || reference > chunks.size()) {
                log.warn("Model cited excerpt {} which was not in the prompt; dropping it", reference);
                continue;
            }
            Document chunk = chunks.get(reference - 1);
            Map<String, Object> metadata = chunk.getMetadata();
            citations.add(new CitationResponse(
                    reference,
                    documentId(metadata),
                    asString(metadata.get(ChunkMetadata.FILENAME)),
                    pageNumber(metadata),
                    asInt(metadata.get(ChunkMetadata.CHUNK_INDEX)),
                    snippet(chunk.getText()),
                    chunk.getScore()));
        }
        return citations;
    }

    private String snippet(String text) {
        if (text == null) {
            return "";
        }
        int limit = properties.qa().maxSnippetChars();
        String normalized = text.strip().replaceAll("\\s+", " ");
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "…";
    }

    private static UUID documentId(Map<String, Object> metadata) {
        Object raw = metadata.get(ChunkMetadata.DOCUMENT_ID);
        return raw == null ? null : UUID.fromString(raw.toString());
    }

    /**
     * Page numbers come from the PDF reader as {@code page_number} and are absent for plain text.
     */
    private static Integer pageNumber(Map<String, Object> metadata) {
        Object raw = metadata.get(ChunkMetadata.PAGE_NUMBER);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Integer.valueOf(text.trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    private static int asInt(Object raw) {
        return raw instanceof Number number ? number.intValue() : -1;
    }

    private static String asString(Object raw) {
        return raw == null ? null : raw.toString();
    }
}
