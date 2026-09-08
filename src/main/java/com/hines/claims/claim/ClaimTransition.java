package com.hines.claims.claim;

/**
 * A status change that has happened on a {@link Claim} but has not yet been
 * recorded in the audit trail.
 *
 * <p>A plain value, not an entity. The aggregate produces these; the service
 * turns them into persisted audit rows. That direction matters: {@code Claim}
 * stays unaware of how - or whether - its history is stored, and the audit
 * package depends on the claim package rather than the reverse.
 *
 * @param from   the status before the change, {@code null} for the initial
 *               submission because nothing precedes a claim's creation
 * @param to     the status after the change
 * @param detail what made this transition distinctive - the approved amount, the
 *               rejection reason - or {@code null} when the transition speaks for
 *               itself
 */
public record ClaimTransition(ClaimStatus from, ClaimStatus to, String detail) {
}
