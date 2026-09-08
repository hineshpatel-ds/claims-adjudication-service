package com.hines.claims.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@link ClaimEvent}.
 *
 * <p>Only reads and inserts. No update or delete method is exposed here, and the
 * database rejects both anyway (V3 trigger) - but keeping them out of the
 * interface means nobody reaches for one by accident and discovers the constraint
 * the hard way, in production.
 */
@Repository
public interface ClaimEventRepository extends JpaRepository<ClaimEvent, UUID> {

    /**
     * A claim's history, oldest first.
     *
     * <p>Derived query: Spring Data builds the implementation from the method
     * name. {@code OrderByOccurredAtAsc} maps onto the
     * {@code (claim_id, occurred_at)} index from V3, so the database returns rows
     * already in order rather than sorting them.
     */
    List<ClaimEvent> findByClaimIdOrderByOccurredAtAsc(UUID claimId);
}
