package com.hines.claims.audit;

import com.hines.claims.claim.ClaimStatus;

/**
 * What kind of thing happened to a claim.
 *
 * <p>Currently one-to-one with the destination status, so it could be derived
 * rather than stored. It is stored anyway, because an audit trail should record
 * <em>events</em> rather than states: a future "note added" or "document
 * attached" changes no status but still belongs in the history, and the table
 * should not need reshaping to accommodate it.
 *
 * <p>Mirrored by a {@code CHECK} constraint on {@code claim_events.event_type}.
 */
public enum ClaimEventType {

    SUBMITTED,
    REVIEW_STARTED,
    APPROVED,
    REJECTED,
    PAID;

    /**
     * The event type implied by arriving at a given status.
     *
     * <p>Exhaustive switch with no {@code default}: adding a constant to
     * {@link ClaimStatus} makes this fail to compile rather than silently fall
     * through to a wrong value. A {@code default} branch here would turn a new
     * status into a mislabelled audit row - the kind of defect nobody notices
     * until an auditor does.
     */
    public static ClaimEventType forTransitionTo(ClaimStatus status) {
        return switch (status) {
            case SUBMITTED -> SUBMITTED;
            case UNDER_REVIEW -> REVIEW_STARTED;
            case APPROVED -> APPROVED;
            case REJECTED -> REJECTED;
            case PAID -> PAID;
        };
    }
}
