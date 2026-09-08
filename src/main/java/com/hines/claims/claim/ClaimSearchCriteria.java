package com.hines.claims.claim;

import java.time.Instant;

/**
 * The filters a caller may apply when listing claims. Any field may be null,
 * meaning "do not filter on this".
 *
 * <p>A record rather than a pile of method parameters, because
 * {@code search(status, policyNumber, from, to, pageable)} invites the classic
 * mistake of swapping two same-typed arguments - here {@code policyNumber} and a
 * future {@code claimantName} - which compiles cleanly and returns wrong results
 * silently.
 *
 * <p>{@code from} and {@code to} bound {@code submittedAt}, not
 * {@code incidentDate}. "Claims filed last week" and "claims for incidents last
 * week" are different questions, and this answers the first - the one an
 * operations team asks.
 *
 * @param status        exact status match
 * @param policyNumber  exact policy match
 * @param from          inclusive lower bound on submission time
 * @param to            exclusive upper bound on submission time
 */
public record ClaimSearchCriteria(
        ClaimStatus status,
        String policyNumber,
        Instant from,
        Instant to) {

    /**
     * Inclusive lower bound, exclusive upper.
     *
     * <p>Half-open ranges compose without gaps or overlaps: consecutive days
     * tile the timeline exactly, and a claim submitted at midnight belongs to
     * exactly one of them. An inclusive upper bound would count it twice, which
     * is the sort of thing that makes two reports disagree by one row and costs
     * an afternoon to explain.
     */
    public boolean hasTimeRange() {
        return from != null || to != null;
    }
}
