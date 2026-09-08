package com.hines.claims.claim.dto;

import jakarta.validation.constraints.PositiveOrZero;

/**
 * Take a claim into review.
 *
 * <p>Carries only {@code expectedVersion}: the version the caller saw when they
 * last read the claim. The server compares it to the current version and refuses
 * the change if they differ, so a decision can never be made against a stale view
 * of the claim.
 *
 * <p>A body with one field looks like overhead for an action with no other input.
 * It is deliberate - every state-changing endpoint takes the same shape, so no
 * caller has to remember which ones enforce concurrency and which do not.
 */
public record ReviewClaimRequest(

        @PositiveOrZero(message = "expectedVersion must be zero or greater")
        long expectedVersion) {
}
