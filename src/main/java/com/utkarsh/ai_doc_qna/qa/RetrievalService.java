package com.utkarsh.ai_doc_qna.qa;

import com.openai.errors.OpenAIException;
import com.utkarsh.ai_doc_qna.common.exception.AiServiceException;
import com.utkarsh.ai_doc_qna.config.AppProperties;
import com.utkarsh.ai_doc_qna.document.ChunkMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Explicit vector retrieval.
 *
 * <p>Kept separate from answer generation so the retrieved chunks — with their scores and
 * metadata — stay available to build citations from. This is the reason the pipeline does not
 * use {@code QuestionAnswerAdvisor}, which hides them.
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private final VectorStore vectorStore;
    private final AppProperties properties;

    public RetrievalService(VectorStore vectorStore, AppProperties properties) {
        this.vectorStore = vectorStore;
        this.properties = properties;
    }


    /**
     * Searches only the given owner's chunks — a question is never answered from another user's
     * uploads. {@code owner_id} is stamped onto every chunk at ingestion time for exactly this.
     */
    public List<Document> retrieve(String question, UUID ownerId) {
        return search(question, "%s == '%s'".formatted(ChunkMetadata.OWNER_ID, ownerId));
    }

    /**
     * Searches only the given documents' chunks. The caller is responsible for having already
     * verified the requester owns every one of them — this filters by document, not by owner.
     */
    public List<Document> retrieveForDocuments(String question, List<UUID> documentIds) {
        String ids = documentIds.stream()
                .map(id -> "'%s'".formatted(id))
                .collect(Collectors.joining(", "));
        return search(question, "%s in [%s]".formatted(ChunkMetadata.DOCUMENT_ID, ids));
    }

    private List<Document> search(String question, String filterExpression) {
        AppProperties.Qa config = properties.qa();
        List<Document> chunks;
        try {
            // Embedding the question is itself a call to the provider, so it can fail the same
            // ways the chat call can and deserves the same classification.
            chunks = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(question)
                    .topK(config.topK())
                    // Anything below the floor is treated as irrelevant rather than padding the
                    // prompt with noise the model might latch onto.
                    .similarityThreshold(config.similarityThreshold())
                    .filterExpression(filterExpression)
                    .build());
        } catch (OpenAIException ex) {
            throw AiServiceException.translate("Failed to embed the question for retrieval", ex);
        }

        List<Document> results = chunks == null ? List.of() : chunks;
        log.debug("Retrieved {} chunks above threshold {} for question: {}",
                results.size(), config.similarityThreshold(), question);
        return results;
    }
}
