package com.hines.claims.ledger;

/**
 * What kind of movement an entry belongs to.
 *
 * <p>One value today. It exists because a reversal, a recovery, or a premium
 * posting are all foreseeable, and adding one should not mean reshaping the
 * table or reinterpreting existing rows.
 */
public enum LedgerEntryType {
    CLAIM_PAYOUT
}
