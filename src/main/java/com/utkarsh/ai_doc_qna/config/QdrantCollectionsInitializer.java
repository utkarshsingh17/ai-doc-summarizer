package com.utkarsh.ai_doc_qna.config;

import com.utkarsh.ai_doc_qna.common.QdrantSupport;
import com.utkarsh.ai_doc_qna.document.ChunkMetadata;
import com.utkarsh.ai_doc_qna.document.QdrantSourceDocumentRepository;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Collections.VectorParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the {@code documents} collection on first run, and payload indexes on every startup
 * (for {@code documents} and for the chunk {@code vector_store} collection Spring AI itself
 * creates).
 *
 * <p>A managed Qdrant instance (Qdrant Cloud, at least as of server 1.19) rejects a filtered
 * {@code scroll}/{@code count}/{@code search} on an un-indexed keyword field outright —
 * {@code INVALID_ARGUMENT: Index required but not found} — rather than falling back to a full
 * scan the way a local/self-hosted instance does. Every field this app ever filters on
 * (`RetrievalService`'s {@code document_id}/{@code owner_id}, `QdrantSourceDocumentRepository`'s
 * {@code owner_id}/{@code checksum}/{@code status}) needs an explicit index because of that,
 * not just for performance.
 */
@Configuration
public class QdrantCollectionsInitializer {

    private static final Logger log = LoggerFactory.getLogger(QdrantCollectionsInitializer.class);

    @Bean
    public ApplicationRunner qdrantCollectionsRunner(
            QdrantClient client,
            @Value("${spring.ai.vectorstore.qdrant.collection-name}") String vectorStoreCollection) {
        return args -> {
            ensureCollection(client, QdrantSourceDocumentRepository.COLLECTION);

            // vector_store is created by Spring AI's own QdrantVectorStore bean, which
            // initializes earlier in the context refresh than this ApplicationRunner — it
            // already exists by the time this runs.
            ensureIndex(client, vectorStoreCollection, ChunkMetadata.DOCUMENT_ID);
            ensureIndex(client, vectorStoreCollection, ChunkMetadata.OWNER_ID);
            ensureIndex(client, QdrantSourceDocumentRepository.COLLECTION, "owner_id");
            ensureIndex(client, QdrantSourceDocumentRepository.COLLECTION, "checksum");
            ensureIndex(client, QdrantSourceDocumentRepository.COLLECTION, "status");
        };
    }

    private void ensureCollection(QdrantClient client, String collectionName) {
        if (QdrantSupport.await(client.collectionExistsAsync(collectionName))) {
            log.info("Using existing Qdrant collection '{}'", collectionName);
            return;
        }
        QdrantSupport.await(client.createCollectionAsync(collectionName,
                VectorParams.newBuilder().setSize(1).setDistance(Distance.Cosine).build()));
        log.info("Created Qdrant collection '{}'", collectionName);
    }

    /** Creating an index that already exists is a safe no-op, so this runs unconditionally. */
    private void ensureIndex(QdrantClient client, String collectionName, String field) {
        QdrantSupport.await(client.createPayloadIndexAsync(
                collectionName, field, PayloadSchemaType.Keyword, null, null, null, null));
        log.info("Ensured payload index on '{}.{}'", collectionName, field);
    }
}
