package com.hines.claims.claim;

import com.hines.claims.claim.dto.ClaimResponse;
import com.hines.claims.claim.dto.SubmitClaimRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Application logic for claims: transaction boundaries, orchestration, and
 * mapping to and from the API shape.
 *
 * <p>Note what is <em>not</em> here. The lifecycle rules live on {@link Claim}
 * itself, so this class never asks "is this transition allowed?" - it calls the
 * domain method and lets the aggregate refuse. A service that re-checks the rules
 * is a service that can disagree with the domain.
 *
 * <p>Knows nothing about HTTP. No status codes, no request objects, no
 * {@code ResponseEntity}. Adding a queue consumer tomorrow would reuse this class
 * unchanged.
 */
@Service
public class ClaimService {

    private final ClaimRepository repository;
    private final Clock clock;

    /**
     * A {@link Clock} rather than calls to {@code Instant.now()}.
     *
     * <p>Scattered {@code now()} calls are untestable: you cannot assert on a
     * timestamp you do not control, so tests either skip time assertions or become
     * flaky. With an injected clock a test supplies {@code Clock.fixed(...)} and
     * timestamps become exactly assertable.
     *
     * <p>It is also the same reasoning as Lesson 1: a hidden dependency on the
     * system clock is still a dependency. Making it explicit makes it substitutable.
     */
    public ClaimService(ClaimRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Accept a new claim.
     *
     * <p>The entity's factory enforces the invariants, so an invalid request
     * cannot produce a persisted row even if the DTO constraints were bypassed.
     */
    @Transactional
    public ClaimResponse submit(SubmitClaimRequest request) {
        Claim claim = Claim.submit(
                request.policyNumber(),
                request.claimantName(),
                request.claimType(),
                request.claimedAmount(),
                request.incidentDate(),
                clock.instant());

        return ClaimResponse.from(repository.save(claim));
    }

    /**
     * Fetch one claim.
     *
     * <p>{@code readOnly = true} lets Hibernate skip dirty-check snapshots - less
     * memory and less work per read - and signals intent to anyone reading the code.
     *
     * <p>The mapping to {@link ClaimResponse} happens <em>inside</em> the
     * transaction, on purpose. With {@code open-in-view} disabled (ADR-0004) the
     * persistence context closes when this method returns, so an entity handed
     * upward would be detached and any lazy access would throw. Returning a DTO
     * means the web layer never holds a live entity.
     */
    @Transactional(readOnly = true)
    public ClaimResponse findById(UUID id) {
        return repository.findById(id)
                .map(ClaimResponse::from)
                .orElseThrow(() -> new ClaimNotFoundException(id));
    }
}
