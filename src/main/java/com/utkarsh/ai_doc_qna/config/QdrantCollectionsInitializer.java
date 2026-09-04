package com.utkarsh.ai_doc_qna.config;

import com.utkarsh.ai_doc_qna.auth.QdrantUserRepository;
import com.utkarsh.ai_doc_qna.common.QdrantSupport;
import com.utkarsh.ai_doc_qna.document.QdrantSourceDocumentRepository;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the {@code users} and {@code documents} collections on first run.
 *
 * <p>These hold relational-shaped data (accounts, upload metadata + the raw file bytes), not
 * embeddings, so there is nothing for Spring AI's {@code initialize-schema} to manage the way it
 * does for the chunk {@code vector_store} collection — that flag only ever touches the collection
 * backing the injected {@code VectorStore} bean. Both collections carry a single-dimensional dummy
 * vector: Qdrant requires every point to have one, but nothing here is ever searched by similarity,
 * only filtered and scrolled.
 */
@Configuration
public class QdrantCollectionsInitializer {

    private static final Logger log = LoggerFactory.getLogger(QdrantCollectionsInitializer.class);

    @Bean
    public ApplicationRunner qdrantCollectionsRunner(QdrantClient client) {
        return args -> {
            ensureCollection(client, QdrantUserRepository.COLLECTION);
            ensureCollection(client, QdrantSourceDocumentRepository.COLLECTION);
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
}
