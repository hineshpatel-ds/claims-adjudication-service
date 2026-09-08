package com.hines.claims.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@link LedgerEntry}.
 *
 * <p>Reads and inserts only. No update or delete is exposed, and the database
 * rejects both anyway (V4) - but keeping them off the interface means nobody
 * reaches for one and discovers the constraint the hard way.
 */
@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByClaimIdOrderByCreatedAtAsc(UUID claimId);
}
