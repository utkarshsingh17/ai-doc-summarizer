package com.utkarsh.ai_doc_qna.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.qdrant.QdrantContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /**
     * spring-ai-spring-boot-testcontainers registers the {@code QdrantConnectionDetails} factory
     * this {@code @ServiceConnection} needs. Users, document metadata and chunk embeddings all
     * live in this one container now — there is nothing else to spin up.
     */
    @Bean
    @ServiceConnection
    QdrantContainer qdrantContainer() {
        return new QdrantContainer(DockerImageName.parse("qdrant/qdrant:v1.18.2"));
    }
}
