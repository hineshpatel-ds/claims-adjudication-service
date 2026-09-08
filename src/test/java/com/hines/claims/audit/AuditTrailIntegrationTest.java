package com.hines.claims.audit;

import com.hines.claims.TestcontainersConfiguration;
import com.hines.claims.claim.ClaimNotFoundException;
import com.hines.claims.claim.ClaimService;
import com.hines.claims.claim.ClaimStatus;
import com.hines.claims.claim.ClaimType;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The audit trail, against a real PostgreSQL instance.
 *
 * <p>Two properties are being defended. That every transition is recorded - which
 * is a matter of the aggregate recording its own history, so a future transition
 * cannot forget. And that recorded history cannot be altered, which is enforced by
 * a database trigger rather than by convention.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("audit trail")
class AuditTrailIntegrationTest {

    @Autowired
    private ClaimService claimService;

    @Autowired
    private ClaimEventRepository eventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ClaimResponse aSubmittedClaim(String policyNumber) {
        return claimService.submit(new SubmitClaimRequest(
                policyNumber, "Jane Doe", ClaimType.MEDICAL,
                new BigDecimal("900.00"), LocalDate.now().minusDays(4)), null);
    }

    @Test
    void submitting_a_claim_records_its_creation() {
        ClaimResponse claim = aSubmittedClaim("POL-AUDIT-1");

        List<ClaimEventResponse> events = claimService.findEvents(claim.id());

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().eventType()).isEqualTo(ClaimEventType.SUBMITTED);
        assertThat(events.getFirst().fromStatus())
                .describedAs("nothing precedes a claim existing")
                .isNull();
        assertThat(events.getFirst().toStatus()).isEqualTo(ClaimStatus.SUBMITTED);
        assertThat(events.getFirst().actor()).isEqualTo("system");
        assertThat(events.getFirst().occurredAt()).isNotNull();
    }

    @Test
    void a_full_lifecycle_leaves_a_complete_ordered_history() {
        ClaimResponse submitted = aSubmittedClaim("POL-AUDIT-2");
        ClaimResponse reviewed = claimService.review(submitted.id(), submitted.version());
        claimService.approve(reviewed.id(), reviewed.version(), new BigDecimal("600.00"));

        List<ClaimEventResponse> events = claimService.findEvents(submitted.id());

        assertThat(events).hasSize(3);
        assertThat(events).extracting(ClaimEventResponse::eventType)
                .containsExactly(
                        ClaimEventType.SUBMITTED,
                        ClaimEventType.REVIEW_STARTED,
                        ClaimEventType.APPROVED);

        // Each event names both ends of the move it describes, so the history can
        // be replayed without consulting the claim.
        assertThat(events.get(1).fromStatus()).isEqualTo(ClaimStatus.SUBMITTED);
        assertThat(events.get(1).toStatus()).isEqualTo(ClaimStatus.UNDER_REVIEW);
        assertThat(events.get(2).fromStatus()).isEqualTo(ClaimStatus.UNDER_REVIEW);
        assertThat(events.get(2).toStatus()).isEqualTo(ClaimStatus.APPROVED);
    }

    @Test
    void an_approval_records_the_amount_it_was_approved_for() {
        ClaimResponse submitted = aSubmittedClaim("POL-AUDIT-3");
        ClaimResponse reviewed = claimService.review(submitted.id(), submitted.version());
        claimService.approve(reviewed.id(), reviewed.version(), new BigDecimal("125.50"));

        ClaimEventResponse approval = claimService.findEvents(submitted.id()).getLast();

        assertThat(approval.detail())
                .describedAs("an auditor asking why it paid this much gets an answer")
                .contains("125.50")
                .contains("900.00");
    }

    @Test
    void a_rejection_records_its_reason() {
        ClaimResponse submitted = aSubmittedClaim("POL-AUDIT-4");
        ClaimResponse reviewed = claimService.review(submitted.id(), submitted.version());
        claimService.reject(reviewed.id(), reviewed.version(), "Treatment not covered under this policy");

        ClaimEventResponse rejection = claimService.findEvents(submitted.id()).getLast();

        assertThat(rejection.eventType()).isEqualTo(ClaimEventType.REJECTED);
        assertThat(rejection.detail()).isEqualTo("Treatment not covered under this policy");
    }

    @Test
    void a_refused_transition_leaves_no_trace() {
        ClaimResponse submitted = aSubmittedClaim("POL-AUDIT-5");

        // Skipping review is illegal, so nothing happened - and an audit trail that
        // recorded attempts as though they were events would be actively misleading.
        assertThatThrownBy(() -> claimService.approve(
                submitted.id(), submitted.version(), new BigDecimal("100.00")))
                .isInstanceOf(RuntimeException.class);

        assertThat(claimService.findEvents(submitted.id()))
                .describedAs("only the submission should be recorded")
                .hasSize(1);
    }

    @Test
    void history_for_an_unknown_claim_is_a_not_found_rather_than_an_empty_list() {
        assertThatExceptionOfType(ClaimNotFoundException.class)
                .isThrownBy(() -> claimService.findEvents(UUID.randomUUID()));
    }

    /**
     * The property that makes this an audit trail rather than a log table.
     *
     * <p>Application code can be changed, bypassed, or simply wrong. A cleanup
     * script, an ORM cascade, or a migration that "tidies" old rows could all
     * rewrite history. The V3 trigger means none of them can - the refusal comes
     * from the database, below anything application code can reach.
     */
    @Test
    void recorded_history_cannot_be_altered_even_by_direct_sql() {
        ClaimResponse claim = aSubmittedClaim("POL-AUDIT-6");
        UUID eventId = eventRepository.findByClaimIdOrderByOccurredAtAsc(claim.id())
                .getFirst().getId();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE claim_events SET actor = 'someone else' WHERE id = ?", eventId))
                .describedAs("an audit row must not be editable")
                // Asserting the type too: V5 gives the trigger a real SQLSTATE so
                // this is a DataIntegrityViolationException (409) rather than an
                // unclassifiable P0001 that Spring reports as bad SQL grammar (500).
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("append-only");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM claim_events WHERE id = ?", eventId))
                .describedAs("an audit row must not be deletable")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("append-only");

        // And it is still there, unchanged.
        ClaimEvent survivor = eventRepository.findById(eventId).orElseThrow();
        assertThat(survivor.getActor()).isEqualTo("system");
    }
}
