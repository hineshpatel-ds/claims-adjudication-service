package com.hines.claims.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One side of one movement of money. Written once, never changed.
 *
 * <p>No setters and no {@code @Version}: there is no second write to conflict
 * with. The database refuses UPDATE and DELETE outright (V4). A mistake is
 * corrected by writing a reversing entry, which is how accounting has worked for
 * six hundred years - the history of what was believed at the time is part of the
 * record, not noise to be cleaned up.
 */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry implements Persistable<UUID> {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "claim_id", nullable = false, updatable = false)
    private UUID claimId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private LedgerAccount account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private EntryDirection direction;

    /** Always positive. The sign lives in {@link #direction}. */
    @Column(nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, updatable = false)
    private LedgerEntryType entryType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    /** Required by JPA. */
    protected LedgerEntry() {
    }

    private LedgerEntry(UUID id, UUID transactionId, UUID claimId, LedgerAccount account,
                        EntryDirection direction, BigDecimal amount, String currency,
                        LedgerEntryType entryType, Instant createdAt) {
        this.id = id;
        this.transactionId = transactionId;
        this.claimId = claimId;
        this.account = account;
        this.direction = direction;
        this.amount = amount;
        this.currency = currency;
        this.entryType = entryType;
        this.createdAt = createdAt;
    }

    /**
     * The balanced pair of entries for paying a claim.
     *
     * <p>Returns both sides together on purpose. There is no public way to create
     * a single entry, so it is not possible to write half a movement and leave the
     * books unbalanced - the type system refuses before the deferred constraint
     * ever has to.
     *
     * <p>Paying a claim debits claims expense (the insurer has now incurred the
     * cost) and credits cash (the money has left). The two are equal, so the
     * transaction sums to zero.
     */
    public static List<LedgerEntry> forPayout(UUID claimId, BigDecimal amount,
                                              String currency, Instant createdAt) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("payout amount must be greater than zero");
        }

        UUID transactionId = UUID.randomUUID();

        return List.of(
                new LedgerEntry(UUID.randomUUID(), transactionId, claimId,
                        LedgerAccount.CLAIMS_EXPENSE, EntryDirection.DEBIT,
                        amount, currency, LedgerEntryType.CLAIM_PAYOUT, createdAt),

                new LedgerEntry(UUID.randomUUID(), transactionId, claimId,
                        LedgerAccount.CASH, EntryDirection.CREDIT,
                        amount, currency, LedgerEntryType.CLAIM_PAYOUT, createdAt));
    }

    /** This entry's contribution to a balance: positive for debits, negative for credits. */
    public BigDecimal signedAmount() {
        return direction.signed(amount);
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markPersisted() {
        this.isNew = false;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public UUID getClaimId() {
        return claimId;
    }

    public LedgerAccount getAccount() {
        return account;
    }

    public EntryDirection getDirection() {
        return direction;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public LedgerEntryType getEntryType() {
        return entryType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
