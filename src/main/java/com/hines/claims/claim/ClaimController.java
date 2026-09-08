package com.hines.claims.claim;

import com.hines.claims.claim.dto.ClaimResponse;
import com.hines.claims.claim.dto.SubmitClaimRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
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
     */
    @PostMapping
    public ResponseEntity<ClaimResponse> submit(@Valid @RequestBody SubmitClaimRequest request,
                                                UriComponentsBuilder uriBuilder) {

        ClaimResponse created = claimService.submit(request);

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
}
