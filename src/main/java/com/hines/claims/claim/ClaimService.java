package com.hines.claims.claim;

import com.hines.claims.audit.ClaimEvent;
import com.hines.claims.audit.ClaimEventRepository;
import com.hines.claims.audit.ClaimEventResponse;
import com.hines.claims.claim.dto.ClaimResponse;
import com.hines.claims.claim.dto.SubmitClaimRequest;
import com.hines.claims.idempotency.IdempotencyKeyConflictException;
import com.hines.claims.idempotency.IdempotencyRecord;
import com.hines.claims.idempotency.IdempotencyRecordRepository;
import com.hines.claims.ledger.LedgerEntry;
import com.hines.claims.ledger.LedgerEntryRepository;
import com.hines.claims.ledger.LedgerEntryResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

    /** Idempotency keys are scoped per operation, so this names the operation. */
    private static final String SUBMIT_ENDPOINT = "POST /api/claims";

    /**
     * Who audit rows are attributed to.
     *
     * <p>This service has no authentication - deliberately out of scope - so there
     * is no authenticated principal to record. Writing "system" is honest about
     * that. With Spring Security this reads from the security context instead, and
     * nothing else about the audit trail changes.
     *
     * <p>Not taken from a request header: a client-supplied identity is
     * unverifiable, and an audit trail recording whatever the caller claimed is
     * worse than one recording nothing, because it looks authoritative.
     */
    private static final String SYSTEM_ACTOR = "system";

    /**
     * The currency every payout is denominated in.
     *
     * <p>A constant because this service is single-currency. Real multi-currency
     * handling means a currency on the claim, exchange rates with effective dates,
     * and rounding rules per currency - a substantial feature, not a column. Naming
     * it here rather than inlining the string marks the assumption.
     */
    private static final String PAYOUT_CURRENCY = "CAD";

    private final ClaimRepository repository;
    private final IdempotencyRecordRepository idempotencyRepository;
    private final ClaimEventRepository eventRepository;
    private final LedgerEntryRepository ledgerRepository;
    private final Clock clock;

    /**
     * A {@link Clock} rather than calls to {@code Instant.now()}.
     *
     * <p>Scattered {@code now()} calls are untestable: you cannot assert on a
     * timestamp you do not control, so tests either skip time assertions or become
     * flaky. With an injected clock a test supplies {@code Clock.fixed(...)} and
     * timestamps become exactly assertable.
     */
    public ClaimService(ClaimRepository repository,
                        IdempotencyRecordRepository idempotencyRepository,
                        ClaimEventRepository eventRepository,
                        LedgerEntryRepository ledgerRepository,
                        Clock clock) {
        this.repository = repository;
        this.idempotencyRepository = idempotencyRepository;
        this.eventRepository = eventRepository;
        this.ledgerRepository = ledgerRepository;
        this.clock = clock;
    }

    /**
     * Accept a new claim.
     *
     * <p>The entity's factory enforces the invariants, so an invalid request
     * cannot produce a persisted row even if the DTO constraints were bypassed.
     */
    @Transactional
    public ClaimResponse submit(SubmitClaimRequest request, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return createClaim(request);
        }

        String fingerprint = IdempotencyRecord.fingerprint(request);

        Optional<IdempotencyRecord> previous = idempotencyRepository.findById(idempotencyKey);
        if (previous.isPresent()) {
            return replay(previous.get(), idempotencyKey, fingerprint);
        }

        // Order matters. The claim is created first, then the key is recorded, and
        // both happen in one transaction - so there is never a claim without its
        // key, nor a key pointing at a claim that was rolled back.
        //
        // Under genuine concurrency two requests can both find no previous record
        // and both proceed. The PRIMARY KEY on idempotency_keys decides it: one
        // insert succeeds, the other violates the constraint and takes its whole
        // transaction down, including its claim. The loser gets a 409 and its
        // retry replays the winner's result. No duplicate survives, and the
        // guarantee is the database's rather than ours.
        ClaimResponse created = createClaim(request);

        idempotencyRepository.saveAndFlush(new IdempotencyRecord(
                idempotencyKey, SUBMIT_ENDPOINT, fingerprint, created.id(), clock.instant()));

        return created;
    }

    /**
     * Return what the original request produced, rather than doing the work again.
     *
     * <p>The fingerprint check matters: the same key with a <em>different</em> body
     * is a client bug, and replaying the first result would silently discard the
     * second request and hand back a claim for something else. Better to fail.
     */
    private ClaimResponse replay(IdempotencyRecord record, String key, String fingerprint) {
        if (!record.getEndpoint().equals(SUBMIT_ENDPOINT)
                || !record.getRequestHash().equals(fingerprint)) {
            throw new IdempotencyKeyConflictException(key);
        }

        return repository.findById(record.getClaimId())
                .map(ClaimResponse::from)
                .orElseThrow(() -> new ClaimNotFoundException(record.getClaimId()));
    }

    private ClaimResponse createClaim(SubmitClaimRequest request) {
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
        recordAudit(claim);
        repository.flush();
        return ClaimResponse.from(claim);
    }

    /**
     * Write the audit rows for whatever the aggregate just did.
     *
     * <p>Called from {@link #respondWith}, which is on every mutating path and no
     * read path - so transitions are always audited and reads never manufacture
     * spurious history.
     *
     * <p>In the same transaction as the change itself, deliberately. A separate
     * transaction would leave a window where a crash commits the state change with
     * no record of it - an audit trail with holes, which is worse than none
     * because it looks complete.
     *
     * <p>The aggregate decides what happened; this only persists it. Nothing here
     * inspects statuses or reconstructs what must have occurred, so a new
     * transition added to {@link Claim} is audited without touching this method.
     */
    private void recordAudit(Claim claim) {
        List<ClaimTransition> transitions = claim.drainPendingTransitions();
        if (transitions.isEmpty()) {
            return;
        }

        Instant occurredAt = clock.instant();
        eventRepository.saveAll(transitions.stream()
                .map(transition -> ClaimEvent.from(claim.getId(), transition, SYSTEM_ACTOR, occurredAt))
                .toList());
    }

    /**
     * A claim's full history, oldest first.
     *
     * <p>Checks the claim exists first, so an unknown id is a 404 rather than an
     * empty list. Those mean different things: "this claim has no history" is
     * impossible - every claim has at least its submission event - so returning
     * {@code []} for a bad id would be quietly misleading.
     */
    @Transactional(readOnly = true)
    public List<ClaimEventResponse> findEvents(UUID claimId) {
        if (!repository.existsById(claimId)) {
            throw new ClaimNotFoundException(claimId);
        }

        return eventRepository.findByClaimIdOrderByOccurredAtAsc(claimId).stream()
                .map(ClaimEventResponse::from)
                .toList();
    }

    /**
     * APPROVED -&gt; PAID, writing the balanced ledger entries for the payment.
     *
     * <p><strong>The amount comes from the stored claim, never from the request.</strong>
     * It was fixed at approval by whoever adjudicated it. Accepting an amount here
     * would let a caller pay a figure nobody approved, which is the single most
     * dangerous thing this API could permit.
     *
     * <p>The claim and its ledger entries are written in one transaction, so the
     * two cannot disagree: there is no state where a claim reads PAID with no
     * money recorded, nor money recorded against a claim that is not PAID. Given
     * that the entries are immutable and the status is not, that consistency has
     * to be atomic - a repair afterwards is not available.
     */
    @Transactional
    public ClaimResponse payout(UUID id, long expectedVersion) {
        Claim claim = loadForUpdate(id, expectedVersion);

        // markPaid enforces that only an APPROVED claim can be paid, so a rejected
        // or already-paid claim throws before any entry is written.
        claim.markPaid(clock.instant());

        ledgerRepository.saveAll(LedgerEntry.forPayout(
                claim.getId(), claim.getApprovedAmount(), PAYOUT_CURRENCY, clock.instant()));

        return respondWith(claim);
    }

    /**
     * The claim's ledger entries, oldest first.
     *
     * <p>Both sides of the movement are returned. One side of a double-entry pair
     * means nothing on its own, and a response showing only the debit would be
     * impossible to reconcile.
     */
    @Transactional(readOnly = true)
    public List<LedgerEntryResponse> findLedgerEntries(UUID claimId) {
        if (!repository.existsById(claimId)) {
            throw new ClaimNotFoundException(claimId);
        }

        return ledgerRepository.findByClaimIdOrderByCreatedAtAsc(claimId).stream()
                .map(LedgerEntryResponse::from)
                .toList();
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
