package com.hines.claims.ledger;

/**
 * The accounts this service posts to.
 *
 * <p>Deliberately two. A real insurer's chart of accounts has hundreds, and
 * modelling that here would add volume without adding anything to demonstrate.
 *
 * <p>Mirrored by a CHECK constraint on {@code ledger_entries.account}.
 */
public enum LedgerAccount {

    /** What the insurer has spent on claims. Debited when a claim is paid. */
    CLAIMS_EXPENSE,

    /** Money the insurer holds. Credited when a claim is paid. */
    CASH
}
