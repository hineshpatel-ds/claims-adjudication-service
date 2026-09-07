package com.hines.claims.claim;

import java.util.UUID;

/**
 * Thrown when a caller attempts a status change the lifecycle does not permit -
 * paying a rejected claim, deciding one twice, reopening a paid one.
 *
 * <p>Unchecked, so it rolls back the surrounding transaction by default. A
 * checked exception would commit (see ADR notes and Lesson 4), which is exactly
 * wrong for a rule violation.
 *
 * <p>Carries the from/to states rather than a pre-formatted message so the web
 * layer can decide how to render it - a 409 body here, a log line there -
 * without parsing a string.
 */
public class IllegalClaimTransitionException extends RuntimeException {

    private final UUID claimId;
    private final ClaimStatus from;
    private final ClaimStatus to;

    public IllegalClaimTransitionException(UUID claimId, ClaimStatus from, ClaimStatus to) {
        super("Claim %s cannot move from %s to %s".formatted(claimId, from, to));
        this.claimId = claimId;
        this.from = from;
        this.to = to;
    }

    public UUID getClaimId() {
        return claimId;
    }

    public ClaimStatus getFrom() {
        return from;
    }

    public ClaimStatus getTo() {
        return to;
    }
}
