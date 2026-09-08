package com.hines.claims.claim;

import com.hines.claims.audit.ClaimEventResponse;
import com.hines.claims.claim.dto.ApproveClaimRequest;
import com.hines.claims.claim.dto.ClaimResponse;
import com.hines.claims.claim.dto.PayoutClaimRequest;
import com.hines.claims.claim.dto.RejectClaimRequest;
import com.hines.claims.claim.dto.ReviewClaimRequest;
import com.hines.claims.claim.dto.SubmitClaimRequest;
import com.hines.claims.ledger.LedgerEntryResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * HTTP entry point for claims.
 *
 * <p>Every method here is three lines or fewer, and that is the design working.
 * A controller translates HTTP into a call and a result back into HTTP. Business
 * logic, transactions, and persistence all live below it.
 *
 * <p>There is no try/catch anywhere in this class. Exceptions propagate to
 * {@code GlobalExceptionHandler}, which turns them into problem details in one
 * place - so error formatting cannot drift between endpoints.
 */
@RestController
@RequestMapping("/api/claims")
public class ClaimController {

    private final ClaimService claimService;

    public ClaimController(ClaimService claimService) {
        this.claimService = claimService;
    }

    /**
     * Submit a new claim.
     *
     * <p>{@code @Valid} runs the DTO constraints before the method body. Without
     * it the annotations on the request record are inert - a silent and very
     * common mistake.
     *
     * <p>Returns 201 with a {@code Location} header naming the new resource, which
     * is what "created" means in HTTP. Returning 200 with a bare body is the
     * common shortcut and tells a client nothing about where the thing now lives.
     *
     * <p>An optional {@code Idempotency-Key} header makes retries safe: a repeat
     * carrying the same key returns the original claim instead of creating a
     * second one. Optional rather than required so the endpoint stays usable from
     * a plain curl, but any client that retries on timeout should send one - which
     * is every client that handles failure properly.
     */
    @PostMapping
    public ResponseEntity<ClaimResponse> submit(
            @Valid @RequestBody SubmitClaimRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            UriComponentsBuilder uriBuilder) {

        ClaimResponse created = claimService.submit(request, idempotencyKey);

        URI location = uriBuilder.path("/api/claims/{id}")
                .buildAndExpand(created.id())
                .toUri();

        return ResponseEntity.created(location).body(created);
    }

    /**
     * Fetch one claim.
     *
     * <p>An unknown id throws {@link ClaimNotFoundException}, which the handler
     * renders as 404. The controller does not check for absence - the service
     * already expressed it as an exception, and duplicating that check here would
     * be a second place for the two to disagree.
     */
    @GetMapping("/{id}")
    public ClaimResponse findById(@PathVariable UUID id) {
        return claimService.findById(id);
    }

    /**
     * SUBMITTED -&gt; UNDER_REVIEW.
     *
     * <p>POST rather than PATCH. PATCH describes editing fields; this is not an
     * edit, it is a named business action with its own rules and its own audit
     * meaning. Modelling transitions as sub-resources keeps the API honest about
     * what is happening - "review this claim", not "set status to UNDER_REVIEW".
     *
     * <p>It also means the client never sends a status. They cannot ask for an
     * arbitrary state; they can only request an action the server knows how to
     * perform, and the server decides what state results.
     */
    @PostMapping("/{id}/review")
    public ClaimResponse review(@PathVariable UUID id,
                                @Valid @RequestBody ReviewClaimRequest request) {
        return claimService.review(id, request.expectedVersion());
    }

    /** UNDER_REVIEW -&gt; APPROVED, for a specific amount. */
    @PostMapping("/{id}/approve")
    public ClaimResponse approve(@PathVariable UUID id,
                                 @Valid @RequestBody ApproveClaimRequest request) {
        return claimService.approve(id, request.expectedVersion(), request.approvedAmount());
    }

    /** UNDER_REVIEW -&gt; REJECTED, with a mandatory reason. */
    @PostMapping("/{id}/reject")
    public ClaimResponse reject(@PathVariable UUID id,
                                @Valid @RequestBody RejectClaimRequest request) {
        return claimService.reject(id, request.expectedVersion(), request.reason());
    }

    /**
     * APPROVED -&gt; PAID, writing the balanced ledger entries.
     *
     * <p>The request carries no amount. It was fixed at approval and is read from
     * the stored claim - accepting one here would let a caller pay a figure nobody
     * adjudicated.
     */
    @PostMapping("/{id}/payout")
    public ClaimResponse payout(@PathVariable UUID id,
                                @Valid @RequestBody PayoutClaimRequest request) {
        return claimService.payout(id, request.expectedVersion());
    }

    /**
     * The claim's ledger entries, oldest first.
     *
     * <p>Both sides of each movement are returned. One half of a double-entry pair
     * cannot be reconciled on its own.
     */
    @GetMapping("/{id}/ledger")
    public List<LedgerEntryResponse> ledger(@PathVariable UUID id) {
        return claimService.findLedgerEntries(id);
    }

    /**
     * The claim's full history, oldest first.
     *
     * <p>Read-only by construction: there is no endpoint to add, edit, or remove
     * an audit entry, and the database rejects both UPDATE and DELETE on the
     * table (V3). History is produced as a side effect of things happening, never
     * authored directly.
     */
    @GetMapping("/{id}/events")
    public List<ClaimEventResponse> events(@PathVariable UUID id) {
        return claimService.findEvents(id);
    }
}
