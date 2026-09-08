package com.hines.claims.claim.dto;

import com.hines.claims.claim.Claim;
import com.hines.claims.claim.ClaimStatus;
import com.hines.claims.claim.ClaimType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * The public shape of a claim.
 *
 * <p>Deliberately not the entity. Renaming a column changes the entity and the
 * migration; this record - the API contract - stays as it is. That independence
 * is the whole point of ADR-0006.
 *
 * <p>{@code version} is exposed because clients need it for optimistic locking:
 * they send it back on updates so the server can detect a concurrent change.
 * It is the one internal-looking field with a genuine reason to be public.
 *
 * <p>{@code allowedNextStates} is included because it saves every client from
 * reimplementing the state machine to decide which buttons to enable. The server
 * owns the rules and says what is possible next.
 */
public record ClaimResponse(
        UUID id,
        String policyNumber,
        String claimantName,
        ClaimType claimType,
        BigDecimal claimedAmount,
        BigDecimal approvedAmount,
        ClaimStatus status,
        Set<ClaimStatus> allowedNextStates,
        LocalDate incidentDate,
        Instant submittedAt,
        Instant decidedAt,
        Instant paidAt,
        String rejectionReason,
        long version) {

    /**
     * Mapping lives here rather than in the controller so every caller produces
     * the same shape, and the controller stays a translator.
     *
     * <p>Hand-written rather than reflection-based: it is a handful of lines, it
     * fails at compile time when a field changes, and a new entity field is never
     * exposed by accident - it has to be added here on purpose.
     */
    public static ClaimResponse from(Claim claim) {
        return new ClaimResponse(
                claim.getId(),
                claim.getPolicyNumber(),
                claim.getClaimantName(),
                claim.getClaimType(),
                claim.getClaimedAmount(),
                claim.getApprovedAmount(),
                claim.getStatus(),
                claim.getStatus().allowedNextStates(),
                claim.getIncidentDate(),
                claim.getSubmittedAt(),
                claim.getDecidedAt(),
                claim.getPaidAt(),
                claim.getRejectionReason(),
                claim.getVersion());
    }
}
