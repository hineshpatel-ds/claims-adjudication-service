package com.hines.claims.claim.dto;

import jakarta.validation.constraints.PositiveOrZero;

/**
 * Pay an approved claim.
 *
 * <p>Carries no amount. The amount was fixed at approval and is read from the
 * stored claim - letting a caller supply it here would allow paying a figure
 * nobody adjudicated, which is the single most dangerous thing this API could
 * permit.
 *
 * <p>So only {@code expectedVersion}, guarding against paying against a stale
 * view of the claim.
 */
public record PayoutClaimRequest(

        @PositiveOrZero(message = "expectedVersion must be zero or greater")
        long expectedVersion) {
}
