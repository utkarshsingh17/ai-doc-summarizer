package com.utkarsh.ai_doc_qna.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.qdrant.QdrantContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"));
    }

    /**
     * spring-ai-spring-boot-testcontainers registers the {@code QdrantConnectionDetails} factory
     * this {@code @ServiceConnection} needs, the same way Postgres gets its support natively from
     * spring-boot-testcontainers.
     */
    @Bean
    @ServiceConnection
    QdrantContainer qdrantContainer() {
        return new QdrantContainer(DockerImageName.parse("qdrant/qdrant:v1.18.2"));
    }
}
