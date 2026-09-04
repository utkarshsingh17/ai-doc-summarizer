package com.utkarsh.ai_doc_qna.common;

import com.google.common.util.concurrent.ListenableFuture;
import com.utkarsh.ai_doc_qna.common.exception.StorageException;
import io.qdrant.client.grpc.JsonWithInt.Value;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static io.qdrant.client.ValueFactory.nullValue;
import static io.qdrant.client.ValueFactory.value;

/**
 * Shared plumbing for the hand-written Qdrant-backed repositories ({@code users}, {@code
 * documents} collections). Qdrant's Java client is fully async ({@link ListenableFuture}); every
 * caller here is a plain synchronous service method, so blocking on the future is the point, not
 * an oversight.
 */
public final class QdrantSupport {

    private QdrantSupport() {
    }

    public static <T> T await(ListenableFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new StorageException("Interrupted while waiting on Qdrant", ex);
        } catch (ExecutionException ex) {
            throw new StorageException("Qdrant request failed", ex.getCause() != null ? ex.getCause() : ex);
        }
    }

    public static Value ofNullable(String value) {
        return value == null ? nullValue() : value(value);
    }

    public static String getString(Map<String, Value> payload, String key) {
        Value found = payload.get(key);
        return found == null || found.hasNullValue() ? null : found.getStringValue();
    }

    public static long getLong(Map<String, Value> payload, String key) {
        Value found = payload.get(key);
        return found == null ? 0 : found.getIntegerValue();
    }

    public static int getInt(Map<String, Value> payload, String key) {
        return (int) getLong(payload, key);
    }

    public static Instant getInstant(Map<String, Value> payload, String key) {
        String raw = getString(payload, key);
        return raw == null ? null : Instant.parse(raw);
    }
}
