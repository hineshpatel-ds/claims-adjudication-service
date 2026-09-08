package com.hines.claims.ledger;

/**
 * Which way money moves in a ledger entry.
 *
 * <p>Amounts are always positive; the direction carries the sign. Allowing
 * negative amounts would make every query ambiguous - is -50 a credit of 50, or a
 * debit entered wrongly? Accounting has avoided that question for centuries.
 */
public enum EntryDirection {

    /** Increases an expense or asset account. */
    DEBIT,

    /** Decreases an expense or asset account. */
    CREDIT;

    /** The signed contribution of an amount in this direction, for summing. */
    public java.math.BigDecimal signed(java.math.BigDecimal amount) {
        return this == DEBIT ? amount : amount.negate();
    }
}
