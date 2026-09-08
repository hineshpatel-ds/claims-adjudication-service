package com.hines.claims.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@link IdempotencyRecord}, keyed by the client-supplied string.
 */
@Repository
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, String> {
}
