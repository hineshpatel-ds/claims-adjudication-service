package com.hines.claims.claim;

import java.util.UUID;

/**
 * Thrown when a claim id does not resolve.
 *
 * <p>A domain exception rather than a Spring or HTTP one. The service layer must
 * not know that "not found" means 404 - that mapping happens once, in the
 * exception handler. Keeping it that way is what lets the same service back a
 * message consumer or a batch job without dragging HTTP semantics along.
 */
public class ClaimNotFoundException extends RuntimeException {

    private final UUID claimId;

    public ClaimNotFoundException(UUID claimId) {
        super("Claim %s not found".formatted(claimId));
        this.claimId = claimId;
    }

    public UUID getClaimId() {
        return claimId;
    }
}
