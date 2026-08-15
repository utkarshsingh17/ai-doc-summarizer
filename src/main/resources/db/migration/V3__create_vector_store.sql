-- Schema for Spring AI's PgVectorStore. We own it here rather than letting the store
-- auto-create it (`spring.ai.vectorstore.pgvector.initialize-schema` stays false), so the
-- schema is versioned and startup ordering is deterministic.
--
-- Column names are fixed by PgVectorSchemaValidator, which verifies id/content/metadata/embedding
-- exist and that the embedding dimension matches the configured one. PgVectorStore binds metadata
-- as `?::jsonb` and filters with `metadata::jsonb @@ ...`, so jsonb is the natural column type.
--
-- vector(1536) matches OpenAI text-embedding-3-small. Changing the embedding model means a new
-- migration and a full re-embed of every document.
CREATE TABLE vector_store (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content   TEXT,
    metadata  JSONB,
    embedding VECTOR(1536)
);

-- Cosine distance (<=>) is the operator PgVectorStore uses with COSINE_DISTANCE, its default.
CREATE INDEX spring_ai_vector_index ON vector_store USING HNSW (embedding vector_cosine_ops);

-- Deleting a document removes its chunks via a metadata filter on document_id; without this
-- index that becomes a full scan of the chunk table.
CREATE INDEX idx_vector_store_metadata ON vector_store USING GIN (metadata jsonb_path_ops);
