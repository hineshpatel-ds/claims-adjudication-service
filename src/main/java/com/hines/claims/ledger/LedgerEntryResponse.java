package com.hines.claims.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The public shape of one ledger entry.
 *
 * <p>Exposes {@code transactionId} so a caller can see which entries belong to
 * the same movement - a single side of a double-entry pair is meaningless on its
 * own, and hiding the grouping would make the response impossible to reconcile.
 */
public record LedgerEntryResponse(
        UUID id,
        UUID transactionId,
        LedgerAccount account,
        EntryDirection direction,
        BigDecimal amount,
        String currency,
        LedgerEntryType entryType,
        Instant createdAt) {

    public static LedgerEntryResponse from(LedgerEntry entry) {
        return new LedgerEntryResponse(
                entry.getId(),
                entry.getTransactionId(),
                entry.getAccount(),
                entry.getDirection(),
                entry.getAmount(),
                entry.getCurrency(),
                entry.getEntryType(),
                entry.getCreatedAt());
    }
}
