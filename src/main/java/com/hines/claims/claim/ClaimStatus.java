package com.hines.claims.claim;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The lifecycle of a claim, and the only transitions that are legal.
 *
 * <pre>
 *   SUBMITTED -> UNDER_REVIEW -> APPROVED -> PAID
 *                             -> REJECTED
 * </pre>
 *
 * <p>The rules live here rather than in the service layer for one reason: there
 * is exactly one place to look, and exactly one place to change. A transition
 * rule spread across controller checks and service {@code if} statements will
 * eventually disagree with itself.
 *
 * <p>Terminal states have no legal transitions out. A paid claim cannot be
 * reopened and a rejected claim cannot later be paid.
 */
public enum ClaimStatus {

    /** Filed by the member, not yet picked up. */
    SUBMITTED,

    /** An adjuster is assessing it. */
    UNDER_REVIEW,

    /** Adjudicated in the member's favour; awaiting payout. */
    APPROVED,

    /** Adjudicated against the member. Terminal. */
    REJECTED,

    /** Money has moved and a ledger entry exists. Terminal. */
    PAID;

    /**
     * Declared as a static map rather than a field on each constant because a
     * constant cannot reference another constant in its own constructor - they
     * do not all exist yet at that point. Static initialisation runs after every
     * constant is constructed, so this is safe.
     */
    private static final Map<ClaimStatus, Set<ClaimStatus>> LEGAL_TRANSITIONS = Map.of(
            SUBMITTED,    EnumSet.of(UNDER_REVIEW),
            UNDER_REVIEW, EnumSet.of(APPROVED, REJECTED),
            APPROVED,     EnumSet.of(PAID),
            REJECTED,     EnumSet.noneOf(ClaimStatus.class),
            PAID,         EnumSet.noneOf(ClaimStatus.class)
    );

    /** Whether moving from this status to {@code target} is permitted. */
    public boolean canTransitionTo(ClaimStatus target) {
        return LEGAL_TRANSITIONS.get(this).contains(target);
    }

    /** Whether this status admits no further transitions. */
    public boolean isTerminal() {
        return LEGAL_TRANSITIONS.get(this).isEmpty();
    }

    /** The statuses reachable from this one. Empty for terminal states. */
    public Set<ClaimStatus> allowedNextStates() {
        return Set.copyOf(LEGAL_TRANSITIONS.get(this));
    }
}
