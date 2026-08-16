-- Checksum uniqueness must be per-owner, not global: two different users legitimately
-- uploading the same file content is not a duplicate from the app's point of view. The old
-- global UNIQUE constraint would let one user's upload collide with an unrelated user's row.
ALTER TABLE source_documents DROP CONSTRAINT source_documents_checksum_key;
ALTER TABLE source_documents ADD CONSTRAINT uq_source_documents_owner_checksum UNIQUE (owner_id, checksum);
