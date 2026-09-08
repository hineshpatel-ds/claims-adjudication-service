package com.hines.claims.claim.dto;

import com.hines.claims.claim.ClaimType;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What a client may supply when submitting a claim.
 *
 * <p>Note what is <em>absent</em>: no {@code status}, no {@code approvedAmount},
 * no {@code version}, no {@code id}. Those are decided by the service, and a
 * client cannot set them because there is nowhere to put them. The mass-assignment
 * problem is not filtered out here - it is unrepresentable (ADR-0006).
 *
 * <p>A record because a DTO is data with no behaviour: immutable, and Jackson
 * binds to it natively. Entities are the opposite case - they have identity and
 * mutable state, and JPA requires a no-arg constructor.
 *
 * <p>These constraints answer "is the request well-formed?" and produce a precise
 * 400. The same rules exist again in {@code Claim.submit} answering "is this state
 * legal?", which holds for every caller including batch jobs and tests. The
 * overlap is deliberate - see Lesson 5.
 */
public record SubmitClaimRequest(

        @NotBlank(message = "policyNumber is required")
        @Size(max = 64, message = "policyNumber must be at most 64 characters")
        String policyNumber,

        @NotBlank(message = "claimantName is required")
        @Size(max = 200, message = "claimantName must be at most 200 characters")
        String claimantName,

        /** Jackson rejects an unknown value with a 400 before validation runs. */
        @NotNull(message = "claimType is required")
        ClaimType claimType,

        /**
         * {@code @Digits} matches NUMERIC(12,2) in the schema. Without it a client
         * could send three decimal places and have them silently rounded on the
         * way into the database - a small, invisible discrepancy in a money field.
         */
        @NotNull(message = "claimedAmount is required")
        @Positive(message = "claimedAmount must be greater than zero")
        @Digits(integer = 10, fraction = 2,
                message = "claimedAmount must have at most 10 integer digits and 2 decimal places")
        BigDecimal claimedAmount,

        @NotNull(message = "incidentDate is required")
        @PastOrPresent(message = "incidentDate cannot be in the future")
        LocalDate incidentDate) {
}
