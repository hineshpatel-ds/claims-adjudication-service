package com.hines.claims.claim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Behaviour of the Claim aggregate.
 *
 * <p>Every rule here is enforced by the entity itself, so it holds no matter
 * which service, controller, or future caller drives it. That is the argument
 * for putting rules in the domain rather than in a service method.
 */
class ClaimTest {

    private static final Instant NOW = Instant.parse("2026-09-07T12:00:00Z");
    private static final BigDecimal CLAIMED = new BigDecimal("1200.00");

    private static Claim aSubmittedClaim() {
        return Claim.submit("POL-123456", "Jane Doe", ClaimType.MEDICAL,
                CLAIMED, LocalDate.of(2026, 8, 30), NOW);
    }

    /** Drives a claim to UNDER_REVIEW, the precondition for adjudication. */
    private static Claim aClaimUnderReview() {
        Claim claim = aSubmittedClaim();
        claim.startReview();
        return claim;
    }

    @Nested
    @DisplayName("submission")
    class Submission {

        @Test
        void a_new_claim_starts_submitted_with_no_decision() {
            Claim claim = aSubmittedClaim();

            assertThat(claim.getStatus()).isEqualTo(ClaimStatus.SUBMITTED);
            assertThat(claim.getId()).isNotNull();
            assertThat(claim.getSubmittedAt()).isEqualTo(NOW);
            assertThat(claim.getApprovedAmount()).isNull();
            assertThat(claim.getDecidedAt()).isNull();
            assertThat(claim.getPaidAt()).isNull();
            assertThat(claim.getRejectionReason()).isNull();
        }

        @Test
        void each_claim_gets_its_own_id() {
            assertThat(aSubmittedClaim().getId()).isNotEqualTo(aSubmittedClaim().getId());
        }

        @ParameterizedTest
        @ValueSource(strings = {"0.00", "-0.01", "-500.00"})
        void a_claim_must_be_for_a_positive_amount(String amount) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Claim.submit("POL-1", "Jane Doe", ClaimType.MEDICAL,
                            new BigDecimal(amount), LocalDate.of(2026, 8, 30), NOW))
                    .withMessageContaining("greater than zero");
        }

        @Test
        void an_incident_cannot_be_in_the_future() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Claim.submit("POL-1", "Jane Doe", ClaimType.MEDICAL,
                            CLAIMED, LocalDate.now().plusDays(1), NOW))
                    .withMessageContaining("future");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        void policy_number_must_not_be_blank(String policyNumber) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Claim.submit(policyNumber, "Jane Doe", ClaimType.MEDICAL,
                            CLAIMED, LocalDate.of(2026, 8, 30), NOW))
                    .withMessageContaining("policyNumber");
        }

        @Test
        void claimant_name_must_not_be_blank() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Claim.submit("POL-1", "  ", ClaimType.MEDICAL,
                            CLAIMED, LocalDate.of(2026, 8, 30), NOW))
                    .withMessageContaining("claimantName");
        }
    }

    @Nested
    @DisplayName("the approval path")
    class ApprovalPath {

        @Test
        void a_claim_can_be_reviewed_approved_and_paid() {
            Claim claim = aSubmittedClaim();

            claim.startReview();
            assertThat(claim.getStatus()).isEqualTo(ClaimStatus.UNDER_REVIEW);

            claim.approve(CLAIMED, NOW.plusSeconds(60));
            assertThat(claim.getStatus()).isEqualTo(ClaimStatus.APPROVED);
            assertThat(claim.getApprovedAmount()).isEqualByComparingTo(CLAIMED);
            assertThat(claim.getDecidedAt()).isEqualTo(NOW.plusSeconds(60));

            claim.markPaid(NOW.plusSeconds(120));
            assertThat(claim.getStatus()).isEqualTo(ClaimStatus.PAID);
            assertThat(claim.getPaidAt()).isEqualTo(NOW.plusSeconds(120));
        }

        @Test
        void a_claim_may_be_approved_for_less_than_claimed() {
            Claim claim = aClaimUnderReview();

            claim.approve(new BigDecimal("450.00"), NOW);

            assertThat(claim.getApprovedAmount()).isEqualByComparingTo("450.00");
            assertThat(claim.getClaimedAmount()).isEqualByComparingTo(CLAIMED);
        }

        @Test
        void a_claim_may_be_approved_for_zero() {
            Claim claim = aClaimUnderReview();

            claim.approve(BigDecimal.ZERO, NOW);

            assertThat(claim.getStatus()).isEqualTo(ClaimStatus.APPROVED);
            assertThat(claim.getApprovedAmount()).isEqualByComparingTo("0");
        }

        @Test
        void a_claim_can_never_be_approved_for_more_than_was_claimed() {
            Claim claim = aClaimUnderReview();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> claim.approve(CLAIMED.add(BigDecimal.ONE), NOW))
                    .withMessageContaining("exceeds claimed amount");
        }

        @Test
        void a_negative_approval_is_rejected() {
            Claim claim = aClaimUnderReview();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> claim.approve(new BigDecimal("-1.00"), NOW))
                    .withMessageContaining("negative");
        }

        @Test
        void a_failed_approval_leaves_the_claim_untouched() {
            Claim claim = aClaimUnderReview();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> claim.approve(CLAIMED.add(BigDecimal.ONE), NOW));

            assertThat(claim.getStatus()).isEqualTo(ClaimStatus.UNDER_REVIEW);
            assertThat(claim.getApprovedAmount()).isNull();
            assertThat(claim.getDecidedAt()).isNull();
        }
    }

    @Nested
    @DisplayName("the rejection path")
    class RejectionPath {

        @Test
        void a_rejected_claim_records_its_reason() {
            Claim claim = aClaimUnderReview();

            claim.reject("Treatment not covered under this policy", NOW);

            assertThat(claim.getStatus()).isEqualTo(ClaimStatus.REJECTED);
            assertThat(claim.getRejectionReason()).isEqualTo("Treatment not covered under this policy");
            assertThat(claim.getDecidedAt()).isEqualTo(NOW);
            assertThat(claim.getApprovedAmount()).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        void a_rejection_must_state_a_reason(String reason) {
            Claim claim = aClaimUnderReview();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> claim.reject(reason, NOW))
                    .withMessageContaining("rejection reason");
        }
    }

    @Nested
    @DisplayName("illegal transitions - the rules money depends on")
    class IllegalTransitions {

        @Test
        void a_rejected_claim_can_never_be_paid() {
            Claim claim = aClaimUnderReview();
            claim.reject("Not covered", NOW);

            assertThatExceptionOfType(IllegalClaimTransitionException.class)
                    .isThrownBy(() -> claim.markPaid(NOW))
                    .satisfies(e -> {
                        assertThat(e.getFrom()).isEqualTo(ClaimStatus.REJECTED);
                        assertThat(e.getTo()).isEqualTo(ClaimStatus.PAID);
                    });
        }

        @Test
        void a_claim_cannot_be_paid_twice() {
            Claim claim = aClaimUnderReview();
            claim.approve(CLAIMED, NOW);
            claim.markPaid(NOW);

            assertThatExceptionOfType(IllegalClaimTransitionException.class)
                    .isThrownBy(() -> claim.markPaid(NOW.plusSeconds(1)));
        }

        @Test
        void a_claim_cannot_be_decided_twice() {
            Claim claim = aClaimUnderReview();
            claim.approve(CLAIMED, NOW);

            assertThatExceptionOfType(IllegalClaimTransitionException.class)
                    .isThrownBy(() -> claim.reject("changed my mind", NOW));
        }

        @Test
        void review_cannot_be_skipped() {
            Claim claim = aSubmittedClaim();

            assertThatExceptionOfType(IllegalClaimTransitionException.class)
                    .isThrownBy(() -> claim.approve(CLAIMED, NOW));
        }

        @Test
        void an_unreviewed_claim_cannot_be_paid() {
            Claim claim = aSubmittedClaim();

            assertThatExceptionOfType(IllegalClaimTransitionException.class)
                    .isThrownBy(() -> claim.markPaid(NOW));
        }

        @Test
        void a_paid_claim_cannot_be_reopened() {
            Claim claim = aClaimUnderReview();
            claim.approve(CLAIMED, NOW);
            claim.markPaid(NOW);

            assertThatExceptionOfType(IllegalClaimTransitionException.class)
                    .isThrownBy(claim::startReview);
        }

        @Test
        void the_exception_identifies_the_claim_and_both_states() {
            Claim claim = aSubmittedClaim();

            assertThatExceptionOfType(IllegalClaimTransitionException.class)
                    .isThrownBy(() -> claim.markPaid(NOW))
                    .satisfies(e -> {
                        assertThat(e.getClaimId()).isEqualTo(claim.getId());
                        assertThat(e.getFrom()).isEqualTo(ClaimStatus.SUBMITTED);
                        assertThat(e.getTo()).isEqualTo(ClaimStatus.PAID);
                    });
        }
    }

    @Nested
    @DisplayName("identity")
    class Identity {

        @Test
        void two_claims_with_identical_details_are_not_the_same_claim() {
            assertThat(aSubmittedClaim()).isNotEqualTo(aSubmittedClaim());
        }

        @Test
        void a_claim_equals_itself() {
            Claim claim = aSubmittedClaim();
            assertThat(claim).isEqualTo(claim);
        }

        @Test
        void identity_survives_a_state_change() {
            Claim claim = aSubmittedClaim();
            int before = claim.hashCode();

            claim.startReview();

            assertThat(claim.hashCode()).isEqualTo(before);
            assertThat(claim).isEqualTo(claim);
        }

        @Test
        void a_claim_is_not_equal_to_other_types() {
            assertThat(aSubmittedClaim()).isNotEqualTo("not a claim");
        }
    }
}
