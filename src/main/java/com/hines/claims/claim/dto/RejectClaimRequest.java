package com.hines.claims.claim.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Reject a claim, with a reason.
 *
 * <p>The reason is mandatory and cannot be blank. A rejection without a stated
 * reason is not defensible to a regulator, and it is not answerable to the member
 * who asks why. The same rule is enforced again in {@link
 * com.hines.claims.claim.Claim#reject} and by a {@code CHECK} constraint in the
 * V1 migration - three layers, because "we denied your claim and cannot say why"
 * is the kind of failure that ends up in a complaint file.
 *
 * <p>The minimum length is a deliberate small obstacle to "no" and "n/a". It does
 * not make reasons good, but it makes the laziest ones inconvenient.
 */
public record RejectClaimRequest(

        @PositiveOrZero(message = "expectedVersion must be zero or greater")
        long expectedVersion,

        @NotBlank(message = "reason is required")
        @Size(min = 10, max = 1000, message = "reason must be between 10 and 1000 characters")
        String reason) {
}
