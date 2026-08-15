package com.utkarsh.ai_doc_qna.qa;

import com.openai.errors.OpenAIException;
import com.utkarsh.ai_doc_qna.common.exception.AiServiceException;
import com.utkarsh.ai_doc_qna.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;

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


    public List<Document> retrieve(String question) {
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
