package com.hines.claims.ledger;

import com.hines.claims.TestcontainersConfiguration;
import com.hines.claims.claim.ClaimNotFoundException;
import com.hines.claims.claim.ClaimService;
import com.hines.claims.claim.ClaimStatus;
import com.hines.claims.claim.ClaimType;
import com.hines.claims.claim.IllegalClaimTransitionException;
import com.hines.claims.claim.dto.ClaimResponse;
import com.hines.claims.claim.dto.SubmitClaimRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Payout and the double-entry ledger, against a real PostgreSQL instance.
 *
 * <p>The properties under test are the ones money depends on: every movement
 * balances, nothing can be paid twice, recorded money cannot be edited, and a
 * claim is never PAID without the corresponding entries existing.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("payout ledger")
class PayoutLedgerIntegrationTest {

    @Autowired
    private ClaimService claimService;

    @Autowired
    private LedgerEntryRepository ledgerRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Drives a claim all the way to APPROVED for the given amount. */
    private ClaimResponse anApprovedClaim(String policyNumber, String approvedAmount) {
        ClaimResponse submitted = claimService.submit(new SubmitClaimRequest(
                policyNumber, "Jane Doe", ClaimType.MEDICAL,
                new BigDecimal("1000.00"), LocalDate.now().minusDays(5)), null);

        ClaimResponse reviewed = claimService.review(submitted.id(), submitted.version());
        return claimService.approve(reviewed.id(), reviewed.version(), new BigDecimal(approvedAmount));
    }

    @Test
    void paying_a_claim_writes_a_balanced_pair_of_entries() {
        ClaimResponse approved = anApprovedClaim("POL-PAY-1", "640.00");

        ClaimResponse paid = claimService.payout(approved.id(), approved.version());

        assertThat(paid.status()).isEqualTo(ClaimStatus.PAID);
        assertThat(paid.paidAt()).isNotNull();

        List<LedgerEntry> entries = ledgerRepository.findByClaimIdOrderByCreatedAtAsc(approved.id());
        assertThat(entries).hasSize(2);

        // Both sides belong to one movement.
        assertThat(entries).extracting(LedgerEntry::getTransactionId)
                .describedAs("both halves share a transaction id")
                .containsOnly(entries.getFirst().getTransactionId());

        assertThat(entries).extracting(LedgerEntry::getAccount)
                .containsExactlyInAnyOrder(LedgerAccount.CLAIMS_EXPENSE, LedgerAccount.CASH);

        assertThat(entries).extracting(LedgerEntry::getDirection)
                .containsExactlyInAnyOrder(EntryDirection.DEBIT, EntryDirection.CREDIT);

        // The invariant that defines double-entry.
        BigDecimal balance = entries.stream()
                .map(LedgerEntry::signedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(balance)
                .describedAs("debits and credits must cancel exactly")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void the_amount_paid_is_the_amount_approved_not_the_amount_claimed() {
        ClaimResponse approved = anApprovedClaim("POL-PAY-2", "250.00");

        claimService.payout(approved.id(), approved.version());

        assertThat(ledgerRepository.findByClaimIdOrderByCreatedAtAsc(approved.id()))
                .extracting(LedgerEntry::getAmount)
                .describedAs("claimed 1000, approved 250, so 250 moves")
                .allSatisfy(amount -> assertThat(amount).isEqualByComparingTo("250.00"));
    }

    @Test
    void entries_are_positive_with_the_sign_carried_by_direction() {
        ClaimResponse approved = anApprovedClaim("POL-PAY-3", "99.99");
        claimService.payout(approved.id(), approved.version());

        List<LedgerEntry> entries = ledgerRepository.findByClaimIdOrderByCreatedAtAsc(approved.id());

        assertThat(entries).allSatisfy(entry ->
                assertThat(entry.getAmount().signum())
                        .describedAs("amounts are never negative")
                        .isPositive());

        LedgerEntry debit = entries.stream()
                .filter(e -> e.getDirection() == EntryDirection.DEBIT).findFirst().orElseThrow();
        LedgerEntry credit = entries.stream()
                .filter(e -> e.getDirection() == EntryDirection.CREDIT).findFirst().orElseThrow();

        assertThat(debit.signedAmount()).isEqualByComparingTo("99.99");
        assertThat(credit.signedAmount()).isEqualByComparingTo("-99.99");
    }

    @Test
    void a_claim_cannot_be_paid_twice() {
        ClaimResponse approved = anApprovedClaim("POL-PAY-4", "300.00");
        ClaimResponse paid = claimService.payout(approved.id(), approved.version());

        assertThatExceptionOfType(IllegalClaimTransitionException.class)
                .isThrownBy(() -> claimService.payout(paid.id(), paid.version()));

        assertThat(ledgerRepository.findByClaimIdOrderByCreatedAtAsc(approved.id()))
                .describedAs("still exactly one movement")
                .hasSize(2);
    }

    @Test
    void a_rejected_claim_can_never_be_paid() {
        ClaimResponse submitted = claimService.submit(new SubmitClaimRequest(
                "POL-PAY-5", "Jane Doe", ClaimType.DENTAL,
                new BigDecimal("500.00"), LocalDate.now().minusDays(2)), null);
        ClaimResponse reviewed = claimService.review(submitted.id(), submitted.version());
        ClaimResponse rejected = claimService.reject(reviewed.id(), reviewed.version(), "Not covered by policy");

        assertThatExceptionOfType(IllegalClaimTransitionException.class)
                .isThrownBy(() -> claimService.payout(rejected.id(), rejected.version()));

        assertThat(ledgerRepository.findByClaimIdOrderByCreatedAtAsc(rejected.id()))
                .describedAs("no money may move for a rejected claim")
                .isEmpty();
    }

    @Test
    void an_unapproved_claim_cannot_be_paid() {
        ClaimResponse submitted = claimService.submit(new SubmitClaimRequest(
                "POL-PAY-6", "Jane Doe", ClaimType.VISION,
                new BigDecimal("120.00"), LocalDate.now().minusDays(1)), null);

        assertThatExceptionOfType(IllegalClaimTransitionException.class)
                .isThrownBy(() -> claimService.payout(submitted.id(), submitted.version()));

        assertThat(ledgerRepository.findByClaimIdOrderByCreatedAtAsc(submitted.id())).isEmpty();
    }

    @Test
    void paying_records_an_audit_event_alongside_the_money() {
        ClaimResponse approved = anApprovedClaim("POL-PAY-7", "75.00");
        claimService.payout(approved.id(), approved.version());

        assertThat(claimService.findEvents(approved.id()))
                .describedAs("submitted, reviewed, approved, paid")
                .hasSize(4)
                .last()
                .satisfies(event -> assertThat(event.toStatus()).isEqualTo(ClaimStatus.PAID));
    }

    /**
     * The deferred constraint from V4, tested directly.
     *
     * <p>A single entry leaves the books unbalanced. The check runs at COMMIT
     * rather than per statement - it has to, because entries are inserted one at a
     * time and the pair is legitimately unbalanced in between - so this fails when
     * the transaction tries to commit, not when the row is written.
     */
    @Test
    void a_lone_entry_cannot_be_committed() {
        ClaimResponse approved = anApprovedClaim("POL-PAY-8", "10.00");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO ledger_entries
                    (id, transaction_id, claim_id, account, direction, amount, currency, entry_type, created_at)
                VALUES (?, ?, ?, 'CASH', 'CREDIT', 42.00, 'CAD', 'CLAIM_PAYOUT', ?)
                """, UUID.randomUUID(), UUID.randomUUID(), approved.id(), OffsetDateTime.now(ZoneOffset.UTC)))
                .describedAs("money cannot appear from nowhere")
                // The type matters as much as the refusal. A trigger raising with
                // PL/pgSQL's default P0001 is unclassifiable, so Spring reports
                // BadSqlGrammarException and our handler returns 500 instead of 409.
                // V4 raises 'check_violation' so this is a real integrity failure.
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("does not balance");
    }

    @Test
    void recorded_money_cannot_be_altered_even_by_direct_sql() {
        ClaimResponse approved = anApprovedClaim("POL-PAY-9", "500.00");
        claimService.payout(approved.id(), approved.version());

        UUID entryId = ledgerRepository.findByClaimIdOrderByCreatedAtAsc(approved.id())
                .getFirst().getId();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE ledger_entries SET amount = 99999.00 WHERE id = ?", entryId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("append-only");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM ledger_entries WHERE id = ?", entryId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("append-only");

        assertThat(ledgerRepository.findById(entryId).orElseThrow().getAmount())
                .isEqualByComparingTo("500.00");
    }

    @Test
    void the_ledger_for_an_unknown_claim_is_a_not_found() {
        assertThatExceptionOfType(ClaimNotFoundException.class)
                .isThrownBy(() -> claimService.findLedgerEntries(UUID.randomUUID()));
    }

    @Test
    void an_approved_but_unpaid_claim_has_an_empty_ledger() {
        ClaimResponse approved = anApprovedClaim("POL-PAY-10", "88.00");

        assertThat(claimService.findLedgerEntries(approved.id()))
                .describedAs("approval decides an amount; it does not move money")
                .isEmpty();
    }
}
