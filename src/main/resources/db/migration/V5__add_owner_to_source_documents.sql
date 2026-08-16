-- Nullable: rows uploaded before ownership existed have no owner and simply become invisible
-- to every user's scoped queries. Every new upload always sets it (enforced in SourceDocument).
ALTER TABLE source_documents ADD COLUMN owner_id UUID REFERENCES users(id);

CREATE INDEX idx_source_documents_owner_id ON source_documents (owner_id);
