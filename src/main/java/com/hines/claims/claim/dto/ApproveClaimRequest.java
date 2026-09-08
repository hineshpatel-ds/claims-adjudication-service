package com.hines.claims.claim.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * Approve a claim for a specific amount.
 *
 * <p>{@code approvedAmount} is unconditionally required here, which is the payoff
 * from splitting approve and reject into separate endpoints. A single
 * {@code /decision} endpoint would need it required only when the decision is
 * APPROVE - a cross-field rule, a custom validator, and an error message that
 * depends on another field's value.
 *
 * <p>Zero is allowed: a claim can be adjudicated valid but payable at nothing
 * (deductible not met, for instance). That is a real outcome and is not the same
 * as a rejection, which is why {@code @PositiveOrZero} rather than
 * {@code @Positive}.
 *
 * <p>The upper bound - never more than was claimed - is not expressible as an
 * annotation because it depends on the stored claim. {@link
 * com.hines.claims.claim.Claim#approve} enforces it, which is the right place:
 * it holds for every caller, not just this endpoint.
 */
public record ApproveClaimRequest(

        @PositiveOrZero(message = "expectedVersion must be zero or greater")
        long expectedVersion,

        @NotNull(message = "approvedAmount is required")
        @PositiveOrZero(message = "approvedAmount cannot be negative")
        @Digits(integer = 10, fraction = 2,
                message = "approvedAmount must have at most 10 integer digits and 2 decimal places")
        BigDecimal approvedAmount) {
}
