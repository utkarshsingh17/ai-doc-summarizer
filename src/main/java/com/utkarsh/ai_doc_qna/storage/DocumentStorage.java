package com.utkarsh.ai_doc_qna.storage;

import java.io.InputStream;

/**
 * Port for the object store holding original uploads. Keeping this an interface means the
 * ingestion and document services never see S3 types, and swapping MinIO for real S3 (or a
 * filesystem) is a configuration change rather than a code change.
 */
public interface DocumentStorage {

    /**
     * @return the storage key the object was written under
     */
    String store(String key, InputStream content, long contentLength, String contentType);

    /**
     * Caller owns the returned stream and must close it.
     */
    InputStream retrieve(String key);

    void delete(String key);
}
