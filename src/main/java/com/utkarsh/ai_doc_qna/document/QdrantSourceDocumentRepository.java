package com.utkarsh.ai_doc_qna.document;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.ScrollPoints;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.utkarsh.ai_doc_qna.common.QdrantSupport.await;
import static com.utkarsh.ai_doc_qna.common.QdrantSupport.getInstant;
import static com.utkarsh.ai_doc_qna.common.QdrantSupport.getInt;
import static com.utkarsh.ai_doc_qna.common.QdrantSupport.getLong;
import static com.utkarsh.ai_doc_qna.common.QdrantSupport.getString;
import static com.utkarsh.ai_doc_qna.common.QdrantSupport.ofNullable;
import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;
import static io.qdrant.client.WithPayloadSelectorFactory.exclude;
import static io.qdrant.client.WithPayloadSelectorFactory.include;
import static io.qdrant.client.WithVectorsSelectorFactory.enable;

/**
 * A document's metadata and its originally uploaded bytes live on the same Qdrant point — the
 * {@code content} payload field replaces what used to be an S3 object addressed by a separate
 * storage key. That also means deleting the point (see {@link #delete}) removes both at once,
 * with no separate object-store cleanup step to keep in sync.
 *
 * <p>{@code content} is excluded from every read here except {@link #loadContent}, so listing or
 * fetching a document's status never pulls its (base64, up to ~67 MB for a 50 MB upload) bytes
 * into memory for no reason.
 */
@Repository
public class QdrantSourceDocumentRepository implements SourceDocumentRepository {

    /** Public so {@code QdrantCollectionsInitializer} can create it at startup. */
    public static final String COLLECTION = "documents";

    private static final String FILENAME = "filename";
    private static final String CONTENT_TYPE = "content_type";
    private static final String SIZE_BYTES = "size_bytes";
    private static final String CHECKSUM = "checksum";
    private static final String OWNER_ID = "owner_id";
    private static final String STATUS = "status";
    private static final String CHUNK_COUNT = "chunk_count";
    private static final String ERROR_MESSAGE = "error_message";
    private static final String CREATED_AT = "created_at";
    private static final String UPDATED_AT = "updated_at";
    private static final String CONTENT = "content";

    /**
     * Every metadata read excludes {@link #CONTENT} explicitly rather than relying on a smaller
     * include-list, so a new metadata field never has to be added here too.
     */
    private static final List<String> METADATA_ONLY = List.of(CONTENT);

    /**
     * Qdrant's scroll cursor is built for streaming, not page-number pagination, and this app's
     * per-owner corpus is small — so {@link #findAllByOwnerId} fetches everything matching the
     * owner in one scroll and paginates in memory instead of translating {@code Pageable} into a
     * chain of cursor requests.
     */
    private static final int MAX_DOCUMENTS_PER_OWNER = 10_000;

    private final QdrantClient client;

    public QdrantSourceDocumentRepository(QdrantClient client) {
        this.client = client;
    }

    @Override
    public SourceDocument save(SourceDocument document, byte[] content) {
        Map<String, Value> payload = new java.util.HashMap<>(metadataPayload(document));
        payload.put(CONTENT, value(Base64.getEncoder().encodeToString(content)));
        await(client.upsertAsync(COLLECTION, List.of(PointStruct.newBuilder()
                .setId(id(document.getId()))
                .setVectors(vectors(0f))
                .putAllPayload(payload)
                .build())));
        return document;
    }

    /**
     * A partial update ({@code setPayload}, not {@code upsert}) on purpose: an upsert replaces a
     * point's entire payload, which would silently wipe the stored {@code content} field on every
     * status transition.
     */
    @Override
    public void updateStatus(SourceDocument document) {
        await(client.setPayloadAsync(COLLECTION, metadataPayload(document), id(document.getId()), true, null, null));
    }

    @Override
    public Optional<SourceDocument> findById(UUID id) {
        List<RetrievedPoint> points = await(client.retrieveAsync(COLLECTION, List.of(id(id)),
                exclude(METADATA_ONLY), enable(false), null));
        return points.isEmpty() ? Optional.empty() : Optional.of(toDocument(points.getFirst()));
    }

    @Override
    public Optional<SourceDocument> findByIdAndOwnerId(UUID id, UUID ownerId) {
        return findById(id).filter(document -> document.getOwnerId().equals(ownerId));
    }

    @Override
    public Optional<SourceDocument> findByOwnerIdAndChecksum(UUID ownerId, String checksum) {
        List<RetrievedPoint> points = await(client.scrollAsync(ScrollPoints.newBuilder()
                .setCollectionName(COLLECTION)
                .setFilter(ownerFilter(ownerId, matchKeyword(CHECKSUM, checksum)))
                .setLimit(1)
                .setWithPayload(exclude(METADATA_ONLY))
                .build())).getResultList();
        return points.isEmpty() ? Optional.empty() : Optional.of(toDocument(points.getFirst()));
    }

    @Override
    public long countByOwnerId(UUID ownerId) {
        return await(client.countAsync(COLLECTION,
                Filter.newBuilder().addMust(matchKeyword(OWNER_ID, ownerId.toString())).build(), true));
    }

    @Override
    public long countByOwnerIdAndStatus(UUID ownerId, IngestionStatus status) {
        return await(client.countAsync(COLLECTION,
                ownerFilter(ownerId, matchKeyword(STATUS, status.name())), true));
    }

    @Override
    public Page<SourceDocument> findAllByOwnerId(UUID ownerId, Pageable pageable) {
        List<RetrievedPoint> points = await(client.scrollAsync(ScrollPoints.newBuilder()
                .setCollectionName(COLLECTION)
                .setFilter(Filter.newBuilder().addMust(matchKeyword(OWNER_ID, ownerId.toString())).build())
                .setLimit(MAX_DOCUMENTS_PER_OWNER)
                .setWithPayload(exclude(METADATA_ONLY))
                .build())).getResultList();

        List<SourceDocument> sorted = points.stream()
                .map(QdrantSourceDocumentRepository::toDocument)
                .sorted(comparator(pageable.getSort()))
                .toList();

        int from = Math.min((int) pageable.getOffset(), sorted.size());
        int to = Math.min(from + pageable.getPageSize(), sorted.size());
        return new PageImpl<>(sorted.subList(from, to), pageable, sorted.size());
    }

    @Override
    public byte[] loadContent(UUID id) {
        List<RetrievedPoint> points = await(client.retrieveAsync(COLLECTION, List.of(id(id)),
                include(List.of(CONTENT)), enable(false), null));
        if (points.isEmpty()) {
            throw new IllegalStateException("Document " + id + " has no stored content");
        }
        String base64 = getString(points.getFirst().getPayloadMap(), CONTENT);
        return Base64.getDecoder().decode(base64);
    }

    @Override
    public void delete(SourceDocument document) {
        await(client.deleteAsync(COLLECTION, List.of(id(document.getId()))));
    }

    @Override
    public void deleteAll() {
        // An empty filter matches every point in the collection.
        await(client.deleteAsync(COLLECTION, Filter.newBuilder().build()));
    }

    private static Filter ownerFilter(UUID ownerId, io.qdrant.client.grpc.Common.Condition extra) {
        return Filter.newBuilder()
                .addMust(matchKeyword(OWNER_ID, ownerId.toString()))
                .addMust(extra)
                .build();
    }

    private static Map<String, Value> metadataPayload(SourceDocument document) {
        Map<String, Value> payload = new java.util.HashMap<>();
        payload.put(FILENAME, value(document.getFilename()));
        payload.put(CONTENT_TYPE, value(document.getContentType()));
        payload.put(SIZE_BYTES, value(document.getSizeBytes()));
        payload.put(CHECKSUM, value(document.getChecksum()));
        payload.put(OWNER_ID, value(document.getOwnerId().toString()));
        payload.put(STATUS, value(document.getStatus().name()));
        payload.put(CHUNK_COUNT, value(document.getChunkCount()));
        payload.put(ERROR_MESSAGE, ofNullable(document.getErrorMessage()));
        payload.put(CREATED_AT, value(document.getCreatedAt().toString()));
        payload.put(UPDATED_AT, value(document.getUpdatedAt().toString()));
        return payload;
    }

    private static SourceDocument toDocument(RetrievedPoint point) {
        Map<String, Value> payload = point.getPayloadMap();
        PointId pointId = point.getId();
        return SourceDocument.restore(
                UUID.fromString(pointId.getUuid()),
                getString(payload, FILENAME),
                getString(payload, CONTENT_TYPE),
                getLong(payload, SIZE_BYTES),
                getString(payload, CHECKSUM),
                UUID.fromString(getString(payload, OWNER_ID)),
                IngestionStatus.valueOf(getString(payload, STATUS)),
                getInt(payload, CHUNK_COUNT),
                getString(payload, ERROR_MESSAGE),
                getInstant(payload, CREATED_AT),
                getInstant(payload, UPDATED_AT));
    }

    private static Comparator<SourceDocument> comparator(Sort sort) {
        if (sort.isUnsorted()) {
            return Comparator.comparing(SourceDocument::getCreatedAt);
        }
        Comparator<SourceDocument> comparator = null;
        for (Sort.Order order : sort) {
            Comparator<SourceDocument> propertyComparator = propertyComparator(order.getProperty());
            if (order.isDescending()) {
                propertyComparator = propertyComparator.reversed();
            }
            comparator = comparator == null ? propertyComparator : comparator.thenComparing(propertyComparator);
        }
        return comparator;
    }

    private static Comparator<SourceDocument> propertyComparator(String property) {
        return switch (property) {
            case "filename" -> Comparator.comparing(SourceDocument::getFilename);
            case "sizeBytes" -> Comparator.comparingLong(SourceDocument::getSizeBytes);
            case "status" -> Comparator.comparing(document -> document.getStatus().name());
            case "chunkCount" -> Comparator.comparingInt(SourceDocument::getChunkCount);
            case "updatedAt" -> Comparator.comparing(SourceDocument::getUpdatedAt);
            default -> Comparator.comparing(SourceDocument::getCreatedAt);
        };
    }
}
