package com.hines.claims.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import org.hibernate.proxy.HibernateProxy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A request for money against a policy.
 *
 * <p>Deliberately has <strong>no public setters</strong>. Every change of state
 * goes through a named method that enforces the rules for that transition. The
 * alternative - a bag of getters and setters with the rules living in a service -
 * is the "anemic domain model", and it fails in a specific way: the rules end up
 * duplicated at every call site, and one of those copies is eventually wrong.
 *
 * <p>Here, {@code approve()} is the only way to reach {@link ClaimStatus#APPROVED},
 * so its guarantees hold everywhere by construction. There is no path around them.
 */
@Entity
@Table(name = "claims")
public class Claim {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "policy_number", nullable = false)
    private String policyNumber;

    @Column(name = "claimant_name", nullable = false)
    private String claimantName;

    /**
     * STRING, never ORDINAL. With ORDINAL, JPA stores the enum's position, so
     * inserting a new constant in the middle silently reinterprets every existing
     * row - MEDICAL rows become DENTAL. It is a genuinely catastrophic default
     * and it fails silently. Always name the strategy explicitly.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "claim_type", nullable = false)
    private ClaimType claimType;

    /** BigDecimal, never double. Binary floating point cannot represent 0.1 exactly. */
    @Column(name = "claimed_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal claimedAmount;

    @Column(name = "approved_amount", precision = 12, scale = 2)
    private BigDecimal approvedAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ClaimStatus status;

    @Column(name = "incident_date", nullable = false)
    private LocalDate incidentDate;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    /**
     * Optimistic locking. Hibernate adds {@code WHERE version = ?} to every
     * UPDATE and increments the column. If another transaction changed the row
     * since we read it, zero rows match and Hibernate throws
     * {@code OptimisticLockException} rather than silently overwriting.
     *
     * <p>Without this, two adjusters approving the same claim concurrently would
     * both succeed and one decision would vanish with no trace.
     */
    @Version
    @Column(nullable = false)
    private long version;

    /**
     * Transitions recorded but not yet written to the audit trail.
     *
     * <p>{@code @Transient} - not a column, not persisted, and empty on a freshly
     * loaded entity. It exists only to carry what happened during this unit of
     * work up to the service, which writes the audit rows.
     */
    @Transient
    private final List<ClaimTransition> pendingTransitions = new ArrayList<>();

    /**
     * Required by JPA, which instantiates entities reflectively before populating
     * fields. Protected rather than public so application code cannot create a
     * Claim in an invalid state - use {@link #submit} instead.
     */
    protected Claim() {
    }

    private Claim(UUID id,
                  String policyNumber,
                  String claimantName,
                  ClaimType claimType,
                  BigDecimal claimedAmount,
                  LocalDate incidentDate,
                  Instant submittedAt) {
        this.id = id;
        this.policyNumber = policyNumber;
        this.claimantName = claimantName;
        this.claimType = claimType;
        this.claimedAmount = claimedAmount;
        this.incidentDate = incidentDate;
        this.submittedAt = submittedAt;
        this.status = ClaimStatus.SUBMITTED;
        // The creation event. from is null: nothing precedes a claim existing.
        this.pendingTransitions.add(new ClaimTransition(null, ClaimStatus.SUBMITTED,
                "Claimed %s for %s".formatted(claimedAmount, claimType)));
    }

    /**
     * The only way to create a claim. A new claim is always SUBMITTED - there is
     * no constructor that lets a caller start one in APPROVED.
     *
     * <p>The id is generated here rather than by the database. That matters for
     * idempotency: the caller knows the id before the insert, so a retried
     * request can be recognised as the same request.
     */
    public static Claim submit(String policyNumber,
                               String claimantName,
                               ClaimType claimType,
                               BigDecimal claimedAmount,
                               LocalDate incidentDate,
                               Instant submittedAt) {

        requireText(policyNumber, "policyNumber");
        requireText(claimantName, "claimantName");
        Objects.requireNonNull(claimType, "claimType is required");
        Objects.requireNonNull(claimedAmount, "claimedAmount is required");
        Objects.requireNonNull(incidentDate, "incidentDate is required");
        Objects.requireNonNull(submittedAt, "submittedAt is required");

        if (claimedAmount.signum() <= 0) {
            throw new IllegalArgumentException("claimedAmount must be greater than zero");
        }
        if (incidentDate.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("incidentDate cannot be in the future");
        }

        return new Claim(UUID.randomUUID(), policyNumber, claimantName,
                claimType, claimedAmount, incidentDate, submittedAt);
    }

    /** SUBMITTED -> UNDER_REVIEW. An adjuster has picked the claim up. */
    public void startReview() {
        transitionTo(ClaimStatus.UNDER_REVIEW, null);
    }

    /**
     * UNDER_REVIEW -> APPROVED, fixing the amount payable.
     *
     * <p>The approved amount may be less than claimed - partial approval is
     * normal - but never more, and never negative.
     */
    public void approve(BigDecimal amount, Instant decidedAt) {
        Objects.requireNonNull(amount, "approved amount is required");
        Objects.requireNonNull(decidedAt, "decidedAt is required");

        if (amount.signum() < 0) {
            throw new IllegalArgumentException("approved amount cannot be negative");
        }
        if (amount.compareTo(claimedAmount) > 0) {
            throw new IllegalArgumentException(
                    "approved amount " + amount + " exceeds claimed amount " + claimedAmount);
        }

        transitionTo(ClaimStatus.APPROVED, "Approved for %s of %s claimed".formatted(amount, claimedAmount));
        this.approvedAmount = amount;
        this.decidedAt = decidedAt;
    }

    /** UNDER_REVIEW -> REJECTED. A reason is mandatory; auditors ask. */
    public void reject(String reason, Instant decidedAt) {
        requireText(reason, "rejection reason");
        Objects.requireNonNull(decidedAt, "decidedAt is required");

        transitionTo(ClaimStatus.REJECTED, reason);
        this.rejectionReason = reason;
        this.decidedAt = decidedAt;
    }

    /** APPROVED -> PAID. Called once the ledger entry has been written. */
    public void markPaid(Instant paidAt) {
        Objects.requireNonNull(paidAt, "paidAt is required");

        transitionTo(ClaimStatus.PAID, "Paid %s".formatted(approvedAmount));
        this.paidAt = paidAt;
    }

    /**
     * The single choke point for status changes. Every transition passes through
     * here, so an illegal one is impossible to express regardless of which method
     * was called - and every legal one is recorded for the audit trail.
     *
     * <p>Recording here rather than in the service is what makes the audit trail
     * trustworthy. A future transition method cannot forget to log itself, because
     * the only way to change status is through this method, and this method always
     * records. "Remember to write an audit event" is a rule that holds until
     * someone is in a hurry; this holds structurally.
     */
    private void transitionTo(ClaimStatus target, String detail) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalClaimTransitionException(id, status, target);
        }
        ClaimStatus previous = this.status;
        this.status = target;
        pendingTransitions.add(new ClaimTransition(previous, target, detail));
    }

    /**
     * Hand over the transitions recorded since the last call, and forget them.
     *
     * <p>Draining rather than merely reading: the service persists what it takes,
     * so leaving them in place would write the same audit rows twice on the next
     * call. The list is {@code @Transient} - it lives only for this instance's
     * lifetime and is never a column.
     */
    public List<ClaimTransition> drainPendingTransitions() {
        List<ClaimTransition> drained = List.copyOf(pendingTransitions);
        pendingTransitions.clear();
        return drained;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    public UUID getId() {
        return id;
    }

    public String getPolicyNumber() {
        return policyNumber;
    }

    public String getClaimantName() {
        return claimantName;
    }

    public ClaimType getClaimType() {
        return claimType;
    }

    public BigDecimal getClaimedAmount() {
        return claimedAmount;
    }

    public BigDecimal getApprovedAmount() {
        return approvedAmount;
    }

    public ClaimStatus getStatus() {
        return status;
    }

    public LocalDate getIncidentDate() {
        return incidentDate;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public long getVersion() {
        return version;
    }

    /**
     * Identity is the id and nothing else.
     *
     * <p>Comparing business fields would make two distinct claims that happen to
     * match look equal, and would change an entity's identity when a field is
     * edited - which corrupts any Set or Map holding it.
     *
     * <p>The proxy handling matters: Hibernate may hand back a lazily-loaded
     * subclass, so {@code getClass() != o.getClass()} would wrongly report a
     * proxy and its entity as different objects.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Claim other)) {
            return false;
        }
        return id != null && id.equals(effectiveId(other));
    }

    /**
     * Constant, on purpose.
     *
     * <p>An id-based hash would change the moment an id is assigned, so an entity
     * added to a HashSet while transient becomes unfindable after persisting.
     * A constant keeps the contract intact at the cost of degrading hash lookups
     * to linear scans - irrelevant for the handful of entities held in memory at
     * once, and correctness beats a micro-optimisation here.
     */
    @Override
    public int hashCode() {
        return Claim.class.hashCode();
    }

    private static UUID effectiveId(Claim claim) {
        return claim instanceof HibernateProxy proxy
                ? (UUID) proxy.getHibernateLazyInitializer().getIdentifier()
                : claim.id;
    }

    @Override
    public String toString() {
        return "Claim[id=%s, policy=%s, status=%s, claimed=%s]"
                .formatted(id, policyNumber, status, claimedAmount);
    }
}
