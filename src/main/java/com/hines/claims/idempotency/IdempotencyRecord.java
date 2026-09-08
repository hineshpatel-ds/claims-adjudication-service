package com.hines.claims.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * A record that a particular request has already been carried out.
 *
 * <p>Append-only: written once when a request succeeds, then only read. Nothing
 * updates a row here, so there is no {@code @Version} - there is no second write
 * to conflict with.
 *
 * <p><strong>Implements {@link Persistable} for a reason that is not optional.</strong>
 * Spring Data decides between INSERT and UPDATE like this:
 *
 * <pre>
 *   if (isNew(entity)) em.persist(entity);   // INSERT
 *   else               em.merge(entity);     // SELECT, then INSERT or UPDATE
 * </pre>
 *
 * <p>The default {@code isNew} simply asks whether the id is null. Our id is the
 * client's key, so it is <em>never</em> null, so Spring Data always concluded the
 * row existed and called {@code merge()}. And {@code merge()} does a SELECT
 * first: when a concurrent transaction had already committed this key, it found
 * the row and issued a harmless UPDATE instead of a colliding INSERT.
 *
 * <p>The result was that the primary key never fired, and six simultaneous
 * requests carrying one idempotency key produced six claims - precisely the
 * duplicate the table exists to prevent. The schema was right, the constraint was
 * right, and the guarantee was silently not running.
 *
 * <p>Declaring the entity new forces {@code persist()}, so a duplicate key is a
 * real INSERT that the database rejects. Caught by
 * {@code IdempotentSubmissionIntegrationTest}; no single-threaded test would have
 * noticed, because with one thread merge and persist behave identically.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyRecord implements Persistable<String> {

    @Id
    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(nullable = false, updatable = false)
    private String endpoint;

    @Column(name = "request_hash", nullable = false, updatable = false)
    private String requestHash;

    @Column(name = "claim_id", nullable = false, updatable = false)
    private UUID claimId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Not a column. True for an object we just constructed in Java, false once it
     * has been written or loaded - which is what the callbacks below maintain.
     */
    @Transient
    private boolean isNew = true;

    /** Required by JPA. */
    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(String idempotencyKey, String endpoint, String requestHash,
                             UUID claimId, Instant createdAt) {
        this.idempotencyKey = idempotencyKey;
        this.endpoint = endpoint;
        this.requestHash = requestHash;
        this.claimId = claimId;
        this.createdAt = createdAt;
    }

    @Override
    public String getId() {
        return idempotencyKey;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    /**
     * After an INSERT, or after loading from the database, the row exists - so the
     * entity is no longer new. Without this, a re-save of a loaded record would
     * attempt a second INSERT.
     */
    @PostPersist
    @PostLoad
    void markPersisted() {
        this.isNew = false;
    }

    /**
     * Fingerprints a request body so a repeat can be checked for sameness.
     *
     * <p>SHA-256 of the payload's canonical form. We store the hash rather than
     * the body: the body may contain personal data we have no reason to keep
     * twice, and a fixed-width hash keeps the table small regardless of payload
     * size.
     *
     * <p>Not a security control - it detects a client mistake, not an attack - but
     * SHA-256 costs nothing here and avoids the collisions a shorter digest would
     * eventually produce.
     */
    public static String fingerprint(Object requestBody) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(requestBody.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // Every JVM is required to provide SHA-256; if it is missing, the
            // platform is broken in ways this service cannot sensibly handle.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public UUID getClaimId() {
        return claimId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
