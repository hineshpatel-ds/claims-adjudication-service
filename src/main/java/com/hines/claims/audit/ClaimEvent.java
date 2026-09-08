package com.hines.claims.audit;

import com.hines.claims.claim.ClaimStatus;
import com.hines.claims.claim.ClaimTransition;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * One entry in a claim's history. Written once, never changed.
 *
 * <p>No setters, no {@code @Version}, no update path at all. The database enforces
 * that too, with a trigger that rejects UPDATE and DELETE (see V3) - an audit
 * trail that is append-only only by convention is not one, because its value comes
 * entirely from being unable to lie.
 *
 * <p>Implements {@link Persistable} for the reason documented on
 * {@code IdempotencyRecord}: the id is assigned in Java, so Spring Data's default
 * {@code isNew()} would report false and issue a {@code merge()} - a pointless
 * SELECT before every insert. Here it is a performance matter rather than a
 * correctness one, since these ids are freshly generated and cannot collide, but
 * the fix is the same and the reasoning is worth applying consistently.
 */
@Entity
@Table(name = "claim_events")
public class ClaimEvent implements Persistable<UUID> {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "claim_id", nullable = false, updatable = false)
    private UUID claimId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false)
    private ClaimEventType eventType;

    /** Null for the submission event: nothing precedes a claim's creation. */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false)
    private ClaimStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false)
    private ClaimStatus toStatus;

    @Column(nullable = false, updatable = false)
    private String actor;

    @Column(updatable = false)
    private String detail;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Transient
    private boolean isNew = true;

    /** Required by JPA. */
    protected ClaimEvent() {
    }

    private ClaimEvent(UUID id, UUID claimId, ClaimEventType eventType, ClaimStatus fromStatus,
                       ClaimStatus toStatus, String actor, String detail, Instant occurredAt) {
        this.id = id;
        this.claimId = claimId;
        this.eventType = eventType;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actor = actor;
        this.detail = detail;
        this.occurredAt = occurredAt;
    }

    /**
     * Build an audit row from a transition the aggregate recorded.
     *
     * <p>The only way to create one. There is no constructor that lets a caller
     * invent a history entry with arbitrary from/to values.
     */
    public static ClaimEvent from(UUID claimId, ClaimTransition transition, String actor, Instant occurredAt) {
        return new ClaimEvent(
                UUID.randomUUID(),
                claimId,
                ClaimEventType.forTransitionTo(transition.to()),
                transition.from(),
                transition.to(),
                actor,
                transition.detail(),
                occurredAt);
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markPersisted() {
        this.isNew = false;
    }

    public UUID getClaimId() {
        return claimId;
    }

    public ClaimEventType getEventType() {
        return eventType;
    }

    public ClaimStatus getFromStatus() {
        return fromStatus;
    }

    public ClaimStatus getToStatus() {
        return toStatus;
    }

    public String getActor() {
        return actor;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
