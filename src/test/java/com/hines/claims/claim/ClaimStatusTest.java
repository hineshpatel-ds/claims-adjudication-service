package com.hines.claims.claim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static com.hines.claims.claim.ClaimStatus.APPROVED;
import static com.hines.claims.claim.ClaimStatus.PAID;
import static com.hines.claims.claim.ClaimStatus.REJECTED;
import static com.hines.claims.claim.ClaimStatus.SUBMITTED;
import static com.hines.claims.claim.ClaimStatus.UNDER_REVIEW;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The transition table, tested directly.
 *
 * <p>No Spring, no database - the rules are plain Java, so verifying them costs
 * milliseconds. Tests this cheap get run constantly, which is the point.
 */
class ClaimStatusTest {

    @Nested
    @DisplayName("legal transitions")
    class Legal {

        @Test
        void submitted_can_move_to_under_review() {
            assertThat(SUBMITTED.canTransitionTo(UNDER_REVIEW)).isTrue();
        }

        @Test
        void under_review_can_be_approved_or_rejected() {
            assertThat(UNDER_REVIEW.canTransitionTo(APPROVED)).isTrue();
            assertThat(UNDER_REVIEW.canTransitionTo(REJECTED)).isTrue();
        }

        @Test
        void approved_can_be_paid() {
            assertThat(APPROVED.canTransitionTo(PAID)).isTrue();
        }
    }

    @Nested
    @DisplayName("illegal transitions - the cases that actually matter")
    class Illegal {

        @Test
        void a_rejected_claim_can_never_be_paid() {
            assertThat(REJECTED.canTransitionTo(PAID)).isFalse();
        }

        @Test
        void a_paid_claim_cannot_be_reopened() {
            assertThat(PAID.canTransitionTo(UNDER_REVIEW)).isFalse();
            assertThat(PAID.canTransitionTo(APPROVED)).isFalse();
        }

        @Test
        void review_cannot_be_skipped() {
            assertThat(SUBMITTED.canTransitionTo(APPROVED)).isFalse();
            assertThat(SUBMITTED.canTransitionTo(REJECTED)).isFalse();
            assertThat(SUBMITTED.canTransitionTo(PAID)).isFalse();
        }

        @Test
        void a_decision_cannot_be_reversed() {
            assertThat(APPROVED.canTransitionTo(REJECTED)).isFalse();
            assertThat(REJECTED.canTransitionTo(APPROVED)).isFalse();
        }

        @ParameterizedTest
        @EnumSource(ClaimStatus.class)
        void no_status_can_transition_to_itself(ClaimStatus status) {
            assertThat(status.canTransitionTo(status)).isFalse();
        }
    }

    @Nested
    @DisplayName("terminal states")
    class Terminal {

        @Test
        void paid_and_rejected_are_terminal() {
            assertThat(PAID.isTerminal()).isTrue();
            assertThat(REJECTED.isTerminal()).isTrue();
        }

        @Test
        void everything_else_is_not() {
            assertThat(SUBMITTED.isTerminal()).isFalse();
            assertThat(UNDER_REVIEW.isTerminal()).isFalse();
            assertThat(APPROVED.isTerminal()).isFalse();
        }

        @ParameterizedTest
        @EnumSource(value = ClaimStatus.class, names = {"PAID", "REJECTED"})
        void terminal_states_offer_no_next_state(ClaimStatus status) {
            assertThat(status.allowedNextStates()).isEmpty();
        }
    }

    /**
     * Guards the map itself. If a constant is added to the enum without a
     * transition entry, {@code LEGAL_TRANSITIONS.get(this)} returns null and
     * every call NPEs at runtime. This fails the build instead.
     */
    @ParameterizedTest
    @EnumSource(ClaimStatus.class)
    void every_status_has_a_transition_rule(ClaimStatus status) {
        assertThat(status.allowedNextStates()).isNotNull();
    }
}
