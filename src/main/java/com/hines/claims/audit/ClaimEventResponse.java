package com.hines.claims.audit;

import com.hines.claims.claim.ClaimStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * The public shape of one audit entry.
 *
 * <p>A DTO rather than the entity, for the same reason as everywhere else
 * (ADR-0006) - and with a sharper edge here: audit rows are exactly the data you
 * least want to expose accidentally when a new internal column is added.
 */
public record ClaimEventResponse(
        UUID id,
        ClaimEventType eventType,
        ClaimStatus fromStatus,
        ClaimStatus toStatus,
        String actor,
        String detail,
        Instant occurredAt) {

    public static ClaimEventResponse from(ClaimEvent event) {
        return new ClaimEventResponse(
                event.getId(),
                event.getEventType(),
                event.getFromStatus(),
                event.getToStatus(),
                event.getActor(),
                event.getDetail(),
                event.getOccurredAt());
    }
}
