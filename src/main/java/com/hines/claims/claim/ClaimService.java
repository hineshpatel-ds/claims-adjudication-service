package com.hines.claims.claim;

import com.hines.claims.claim.dto.ClaimResponse;
import com.hines.claims.claim.dto.SubmitClaimRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

        repository.save(claim);
        return respondWith(claim);
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
     * upward would be detached and any lazy access would throw.
     */
    @Transactional(readOnly = true)
    public ClaimResponse findById(UUID id) {
        return repository.findById(id)
                .map(ClaimResponse::from)
                .orElseThrow(() -> new ClaimNotFoundException(id));
    }

    /**
     * SUBMITTED -&gt; UNDER_REVIEW.
     *
     * <p><strong>There is no {@code repository.save()} call, and that is not an
     * omission.</strong> The claim was loaded inside this transaction, so it is a
     * managed entity: Hibernate holds a snapshot of its original field values, and
     * at commit it compares them against the current values and issues an UPDATE
     * for whatever changed. That is dirty checking (Lesson 4).
     *
     * <p>Calling {@code save()} here would work and change nothing. It is left out
     * because writing it implies the write depends on it, and the next person will
     * copy that belief into a method where it is wrong.
     */
    @Transactional
    public ClaimResponse review(UUID id, long expectedVersion) {
        Claim claim = loadForUpdate(id, expectedVersion);
        claim.startReview();
        return respondWith(claim);
    }

    /**
     * UNDER_REVIEW -&gt; APPROVED, fixing the amount payable.
     *
     * <p>The service does not check that the amount is within the claimed amount,
     * nor that the claim is in a state that can be approved. Both live on the
     * aggregate. If they were duplicated here, the two copies would eventually
     * disagree and the weaker one would win on some code path.
     */
    @Transactional
    public ClaimResponse approve(UUID id, long expectedVersion, BigDecimal approvedAmount) {
        Claim claim = loadForUpdate(id, expectedVersion);
        claim.approve(approvedAmount, clock.instant());
        return respondWith(claim);
    }

    /** UNDER_REVIEW -&gt; REJECTED, with a mandatory reason. */
    @Transactional
    public ClaimResponse reject(UUID id, long expectedVersion, String reason) {
        Claim claim = loadForUpdate(id, expectedVersion);
        claim.reject(reason, clock.instant());
        return respondWith(claim);
    }

    /**
     * Flush, then map — and the order is the whole point.
     *
     * <p>Hibernate does not increment {@code @Version} when you call a setter. It
     * increments it when the UPDATE actually runs, at flush. Mapping the entity
     * before that returns the version the row had <em>before</em> this change:
     *
     * <pre>
     *   claim.startReview();                  // in memory, version still 0
     *   return ClaimResponse.from(claim);     // responds "version": 0
     *   // ... transaction commits, UPDATE runs, row is now version 1
     * </pre>
     *
     * <p>The client then sends {@code expectedVersion: 0} on its next call and is
     * refused as stale — for a claim only it has touched. Every read-modify-write
     * client would be broken by this, and no single-transition test would notice,
     * because the response looks perfectly reasonable on its own.
     *
     * <p>Flushing first forces the UPDATE, so Hibernate has incremented the field
     * by the time it is read. The response then carries the version the row
     * actually holds.
     *
     * <p>A useful side effect: database constraint violations surface here, inside
     * the service, rather than during commit after the method has returned — where
     * the stack trace no longer points at the code that caused them.
     */
    private ClaimResponse respondWith(Claim claim) {
        repository.flush();
        return ClaimResponse.from(claim);
    }

    /**
     * Load a claim for modification, refusing a stale view of it.
     *
     * <p>This closes the slow case: an adjuster read the claim, someone else
     * changed it, and the first adjuster now acts on what their screen showed.
     * Rejecting that early means no work is done against out-of-date information.
     *
     * <p>It does <strong>not</strong> close the race. Two threads can both read
     * version 1, both pass this check, and both proceed - there is always a window
     * between our read and our write. The {@code @Version} column closes that at
     * the database, where it is genuinely atomic, by failing the second UPDATE.
     * Two mechanisms because they catch different failures.
     */
    private Claim loadForUpdate(UUID id, long expectedVersion) {
        Claim claim = repository.findById(id)
                .orElseThrow(() -> new ClaimNotFoundException(id));

        if (claim.getVersion() != expectedVersion) {
            throw new StaleClaimVersionException(id, expectedVersion, claim.getVersion());
        }
        return claim;
    }
}
