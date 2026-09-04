package com.utkarsh.ai_doc_qna.auth;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.ScrollPoints;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.utkarsh.ai_doc_qna.common.QdrantSupport.await;
import static com.utkarsh.ai_doc_qna.common.QdrantSupport.getInstant;
import static com.utkarsh.ai_doc_qna.common.QdrantSupport.getString;
import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;
import static io.qdrant.client.WithPayloadSelectorFactory.enable;

@Repository
public class QdrantUserRepository implements UserRepository {

    /** Public so {@code QdrantCollectionsInitializer} can create it at startup. */
    public static final String COLLECTION = "users";

    private static final String EMAIL = "email";
    private static final String PASSWORD_HASH = "password_hash";
    private static final String CREATED_AT = "created_at";

    private final QdrantClient client;

    public QdrantUserRepository(QdrantClient client) {
        this.client = client;
    }

    @Override
    public Optional<User> findByEmail(String email) {
        List<RetrievedPoint> points = await(client.scrollAsync(ScrollPoints.newBuilder()
                .setCollectionName(COLLECTION)
                .setFilter(Filter.newBuilder().addMust(matchKeyword(EMAIL, email)).build())
                .setLimit(1)
                .setWithPayload(enable(true))
                .build())).getResultList();
        return points.isEmpty() ? Optional.empty() : Optional.of(toUser(points.getFirst()));
    }

    @Override
    public boolean existsByEmail(String email) {
        return findByEmail(email).isPresent();
    }

    /**
     * Not race-free: two concurrent registrations for the same email can both pass
     * {@link #existsByEmail} before either writes. Qdrant has no unique constraint to fall back
     * on the way the old Postgres schema did — an accepted trade-off of moving auth storage onto
     * a vector database.
     */
    @Override
    public User save(User user) {
        Map<String, Value> payload = Map.of(
                EMAIL, value(user.email()),
                PASSWORD_HASH, value(user.passwordHash()),
                CREATED_AT, value(user.createdAt().toString()));
        await(client.upsertAsync(COLLECTION, List.of(PointStruct.newBuilder()
                .setId(id(user.id()))
                .setVectors(vectors(0f))
                .putAllPayload(payload)
                .build())));
        return user;
    }

    private static User toUser(RetrievedPoint point) {
        Map<String, Value> payload = point.getPayloadMap();
        PointId pointId = point.getId();
        return new User(
                UUID.fromString(pointId.getUuid()),
                getString(payload, EMAIL),
                getString(payload, PASSWORD_HASH),
                getInstant(payload, CREATED_AT));
    }
}
