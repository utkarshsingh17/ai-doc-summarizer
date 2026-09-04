package com.utkarsh.ai_doc_qna.support;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Replaces the OpenAI chat and embedding models with deterministic stand-ins, so the integration
 * test exercises the full pipeline — object storage, parsing, splitting, Qdrant, citation
 * mapping — without an API key, network access or cost.
 */
@TestConfiguration(proxyBeanMethods = false)
public class StubAiConfiguration {

    public static final int DIMENSIONS = 1536;

    /**
     * Returns the same unit vector for every input. Cosine distance is then 0 for every pair, so
     * everything stored is retrieved with score 1.0 and clears any threshold. That keeps the test
     * about the plumbing rather than about embedding quality, which would otherwise make it flaky.
     */
    @Bean
    @Primary
    public EmbeddingModel stubEmbeddingModel() {
        return new EmbeddingModel() {

            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                List<Embedding> embeddings = new ArrayList<>();
                for (int i = 0; i < request.getInstructions().size(); i++) {
                    embeddings.add(new Embedding(constantVector(), i));
                }
                return new EmbeddingResponse(embeddings);
            }

            @Override
            public float[] embed(Document document) {
                return constantVector();
            }

            @Override
            public int dimensions() {
                return DIMENSIONS;
            }
        };
    }

    /** Lets a test decide what the "model" answers before making the request. */
    @Bean
    public StubChatResponses stubChatResponses() {
        return new StubChatResponses();
    }

    @Bean
    @Primary
    public ChatModel stubChatModel(StubChatResponses responses) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                responses.lastPrompt.set(prompt.getContents());
                return new ChatResponse(List.of(new Generation(new AssistantMessage(responses.nextJson.get()))));
            }
        };
    }

    private static float[] constantVector() {
        float[] vector = new float[DIMENSIONS];
        // A single non-zero component keeps the vector non-degenerate for cosine distance,
        // which is undefined for the zero vector.
        vector[0] = 1.0f;
        return vector;
    }

    /** Mutable holder so tests can set the reply and inspect the prompt that produced it. */
    public static class StubChatResponses {

        private final AtomicReference<String> nextJson = new AtomicReference<>(
                "{\"answer\":\"stub\",\"answerable\":false,\"usedSources\":[]}");

        private final AtomicReference<String> lastPrompt = new AtomicReference<>("");

        public void reply(String answer, boolean answerable, String usedSources) {
            nextJson.set("{\"answer\":\"%s\",\"answerable\":%s,\"usedSources\":[%s]}"
                    .formatted(answer, answerable, usedSources));
        }

        public String lastPrompt() {
            return lastPrompt.get();
        }
    }
}
