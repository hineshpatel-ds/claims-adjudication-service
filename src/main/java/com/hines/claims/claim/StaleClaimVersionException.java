package com.hines.claims.claim;

import java.util.UUID;

/**
 * Thrown when a caller acts on a version of the claim that is no longer current.
 *
 * <p>This is the <em>stale read</em> case, and it is distinct from a database
 * write collision:
 *
 * <ul>
 *   <li><strong>Stale read (this class)</strong> - the adjuster loaded the claim,
 *       went for coffee, and meanwhile someone else changed it. Their screen shows
 *       version 1; the claim is now version 3. Nothing is racing - they are simply
 *       deciding against information that is out of date.</li>
 *   <li><strong>Write collision</strong> ({@code ObjectOptimisticLockingFailureException})
 *       - two transactions both read version 1 and both try to write. The
 *       {@code @Version} column lets exactly one win.</li>
 * </ul>
 *
 * <p>Both surface as 409. Both are needed: the check here catches the slow,
 * common case before any work is done, and the database catches the genuine race
 * that no application-level check can close - because between our read and our
 * write there is always a window.
 */
public class StaleClaimVersionException extends RuntimeException {

    private final UUID claimId;
    private final long expectedVersion;
    private final long actualVersion;

    public StaleClaimVersionException(UUID claimId, long expectedVersion, long actualVersion) {
        super("Claim %s has version %d, but the request expected %d"
                .formatted(claimId, actualVersion, expectedVersion));
        this.claimId = claimId;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public UUID getClaimId() {
        return claimId;
    }

    public long getExpectedVersion() {
        return expectedVersion;
    }

    public long getActualVersion() {
        return actualVersion;
    }
}
