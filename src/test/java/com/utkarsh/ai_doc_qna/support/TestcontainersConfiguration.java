package com.utkarsh.ai_doc_qna.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /**
     * The stock postgres image has no pgvector extension, so V1 would fail. The pgvector image is
     * declared as a substitute so Testcontainers still applies its Postgres wait strategy.
     */
    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    }

    @Bean
    MinIOContainer minioContainer() {
        return new MinIOContainer(DockerImageName.parse("minio/minio:latest"))
                .withUserName("testaccess")
                .withPassword("testsecret");
    }

    /**
     * MinIO has no {@code @ServiceConnection} support, and the endpoint is only known once the
     * container has started, so the {@code app.storage.*} properties are bound here.
     */
    @Bean
    DynamicPropertyRegistrar minioProperties(MinIOContainer minio) {
        return registry -> {
            registry.add("app.storage.endpoint", minio::getS3URL);
            registry.add("app.storage.access-key", minio::getUserName);
            registry.add("app.storage.secret-key", minio::getPassword);
        };
    }
}
