-- One row per uploaded file. The extracted chunks live in the vector store (V3),
-- linked back to this table by the `document_id` key in their metadata.
CREATE TABLE source_documents (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    filename       VARCHAR(512) NOT NULL,
    content_type   VARCHAR(128) NOT NULL,
    size_bytes     BIGINT       NOT NULL,
    -- SHA-256 of the file contents; makes re-uploading the same file a 409 instead of
    -- silently duplicating every chunk in the vector store and skewing retrieval.
    -- VARCHAR, not CHAR: CHAR reports as bpchar and fails Hibernate's schema validation.
    checksum       VARCHAR(64)  NOT NULL UNIQUE,
    storage_key    VARCHAR(1024) NOT NULL,
    status         VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    chunk_count    INTEGER      NOT NULL DEFAULT 0,
    error_message  TEXT,
    version        BIGINT       NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_source_documents_status ON source_documents (status);
CREATE INDEX idx_source_documents_created_at ON source_documents (created_at DESC);
