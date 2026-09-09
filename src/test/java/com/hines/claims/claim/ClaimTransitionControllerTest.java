package com.hines.claims.claim;

import com.hines.claims.audit.ClaimEventResponse;
import com.hines.claims.audit.ClaimEventType;
import com.hines.claims.claim.dto.ClaimResponse;
import com.hines.claims.common.dto.PageResponse;
import com.hines.claims.common.error.GlobalExceptionHandler;
import com.hines.claims.ledger.EntryDirection;
import com.hines.claims.ledger.LedgerAccount;
import com.hines.claims.ledger.LedgerEntryResponse;
import com.hines.claims.ledger.LedgerEntryType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract for the transition and read endpoints.
 *
 * <p>These were built with integration tests at the service level, which verified
 * the behaviour but never exercised the web layer - so DTO validation on the new
 * request records and the 409 mappings were untested. A JaCoCo report made the gap
 * obvious: {@code ClaimController} sat at 39% while the service was above 90%.
 *
 * <p>That is what coverage is genuinely good for. Not a score to hit, but a
 * pointer at code no test has run.
 */
@WebMvcTest(ClaimController.class)
@Import(GlobalExceptionHandler.class)
@DisplayName("transition and read endpoints")
class ClaimTransitionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ClaimService claimService;

    private static final UUID CLAIM_ID = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");

    /** A claim at a given status and version, for stubbing transition responses. */
    private static ClaimResponse claimAt(ClaimStatus status, long version, BigDecimal approved) {
        return new ClaimResponse(CLAIM_ID, "POL-123456", "Jane Doe", ClaimType.MEDICAL,
                new BigDecimal("1200.00"), approved, status, status.allowedNextStates(),
                LocalDate.of(2026, 8, 30), Instant.parse("2026-09-07T12:00:00Z"),
                null, null, null, version);
    }

    @Nested
    @DisplayName("transitions")
    class Transitions {

        @Test
        void review_returns_the_updated_claim() throws Exception {
            given(claimService.review(eq(CLAIM_ID), eq(0L)))
                    .willReturn(claimAt(ClaimStatus.UNDER_REVIEW, 1L, null));

            mockMvc.perform(post("/api/claims/{id}/review", CLAIM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"expectedVersion\":0}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UNDER_REVIEW"))
                    .andExpect(jsonPath("$.version").value(1));
        }

        /**
         * The stale-read case over HTTP. Both versions appear in the body so a UI
         * can say what actually happened rather than "please try again".
         */
        @Test
        void a_stale_expected_version_is_409_carrying_both_versions() throws Exception {
            willThrow(new StaleClaimVersionException(CLAIM_ID, 0L, 2L))
                    .given(claimService).review(CLAIM_ID, 0L);

            mockMvc.perform(post("/api/claims/{id}/review", CLAIM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"expectedVersion\":0}"))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.title").value("Stale claim version"))
                    .andExpect(jsonPath("$.expectedVersion").value(0))
                    .andExpect(jsonPath("$.actualVersion").value(2));
        }

        @Test
        void approve_returns_the_approved_amount() throws Exception {
            given(claimService.approve(eq(CLAIM_ID), eq(1L), any()))
                    .willReturn(claimAt(ClaimStatus.APPROVED, 2L, new BigDecimal("650.00")));

            mockMvc.perform(post("/api/claims/{id}/approve", CLAIM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"expectedVersion\":1,\"approvedAmount\":650.00}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"))
                    .andExpect(jsonPath("$.approvedAmount").value(650.00));
        }

        @Test
        void approve_without_an_amount_is_rejected() throws Exception {
            mockMvc.perform(post("/api/claims/{id}/approve", CLAIM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"expectedVersion\":1}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.approvedAmount").exists());
        }

        @Test
        void approve_with_a_negative_amount_is_rejected() throws Exception {
            mockMvc.perform(post("/api/claims/{id}/approve", CLAIM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"expectedVersion\":1,\"approvedAmount\":-5.00}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.approvedAmount").exists());
        }

        @Test
        void reject_returns_the_rejected_claim() throws Exception {
            given(claimService.reject(eq(CLAIM_ID), eq(1L), any()))
                    .willReturn(claimAt(ClaimStatus.REJECTED, 2L, null));

            mockMvc.perform(post("/api/claims/{id}/reject", CLAIM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"expectedVersion\":1,\"reason\":\"Treatment not covered by policy\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("REJECTED"));
        }

        @Test
        void reject_without_a_reason_is_refused() throws Exception {
            mockMvc.perform(post("/api/claims/{id}/reject", CLAIM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"expectedVersion\":1,\"reason\":\"\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.reason").exists());
        }

        /** The minimum length exists to make "no" and "n/a" inconvenient. */
        @Test
        void a_too_short_rejection_reason_is_refused() throws Exception {
            mockMvc.perform(post("/api/claims/{id}/reject", CLAIM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"expectedVersion\":1,\"reason\":\"no\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.reason").exists());
        }

        @Test
        void payout_returns_the_paid_claim_with_no_further_states() throws Exception {
            given(claimService.payout(eq(CLAIM_ID), anyLong()))
                    .willReturn(claimAt(ClaimStatus.PAID, 3L, new BigDecimal("650.00")));

            mockMvc.perform(post("/api/claims/{id}/payout", CLAIM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"expectedVersion\":2}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PAID"))
                    .andExpect(jsonPath("$.allowedNextStates").isEmpty());
        }

        @Test
        void an_illegal_transition_is_409_naming_both_states() throws Exception {
            willThrow(new IllegalClaimTransitionException(CLAIM_ID, ClaimStatus.REJECTED, ClaimStatus.PAID))
                    .given(claimService).payout(CLAIM_ID, 2L);

            mockMvc.perform(post("/api/claims/{id}/payout", CLAIM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"expectedVersion\":2}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.from").value("REJECTED"))
                    .andExpect(jsonPath("$.to").value("PAID"));
        }
    }

    @Nested
    @DisplayName("reads")
    class Reads {

        @Test
        void the_list_endpoint_returns_our_own_page_shape() throws Exception {
            given(claimService.search(any(), any())).willReturn(new PageResponse<>(
                    List.of(claimAt(ClaimStatus.SUBMITTED, 0L, null)), 0, 20, 1, 1, true, true));

            mockMvc.perform(get("/api/claims?status=SUBMITTED&size=20"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content[0].id").value(CLAIM_ID.toString()))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.page").value(0))
                    .andExpect(jsonPath("$.last").value(true))
                    // Spring's own Page serialises a nested "pageable" object. Its
                    // absence here is the entire point of PageResponse.
                    .andExpect(jsonPath("$.pageable").doesNotExist());
        }

        @Test
        void an_unknown_status_filter_is_400_not_500() throws Exception {
            mockMvc.perform(get("/api/claims?status=NONSENSE"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void the_events_endpoint_returns_the_audit_trail() throws Exception {
            given(claimService.findEvents(CLAIM_ID)).willReturn(List.of(
                    new ClaimEventResponse(UUID.randomUUID(), ClaimEventType.SUBMITTED,
                            null, ClaimStatus.SUBMITTED, "system", "Claimed 1200.00 for MEDICAL",
                            Instant.parse("2026-09-07T12:00:00Z"))));

            mockMvc.perform(get("/api/claims/{id}/events", CLAIM_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].eventType").value("SUBMITTED"))
                    .andExpect(jsonPath("$[0].fromStatus").doesNotExist())
                    .andExpect(jsonPath("$[0].actor").value("system"));
        }

        @Test
        void the_ledger_endpoint_returns_both_sides_of_the_movement() throws Exception {
            UUID transactionId = UUID.randomUUID();

            given(claimService.findLedgerEntries(CLAIM_ID)).willReturn(List.of(
                    new LedgerEntryResponse(UUID.randomUUID(), transactionId, LedgerAccount.CLAIMS_EXPENSE,
                            EntryDirection.DEBIT, new BigDecimal("650.00"), "CAD",
                            LedgerEntryType.CLAIM_PAYOUT, Instant.parse("2026-09-07T12:00:00Z")),
                    new LedgerEntryResponse(UUID.randomUUID(), transactionId, LedgerAccount.CASH,
                            EntryDirection.CREDIT, new BigDecimal("650.00"), "CAD",
                            LedgerEntryType.CLAIM_PAYOUT, Instant.parse("2026-09-07T12:00:00Z"))));

            mockMvc.perform(get("/api/claims/{id}/ledger", CLAIM_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].direction").value("DEBIT"))
                    .andExpect(jsonPath("$[1].direction").value("CREDIT"))
                    // Both halves must share a transaction id, or they cannot be
                    // reconciled as one movement.
                    .andExpect(jsonPath("$[0].transactionId").value(transactionId.toString()))
                    .andExpect(jsonPath("$[1].transactionId").value(transactionId.toString()));
        }

        @Test
        void events_for_an_unknown_claim_are_404() throws Exception {
            willThrow(new ClaimNotFoundException(CLAIM_ID)).given(claimService).findEvents(CLAIM_ID);

            mockMvc.perform(get("/api/claims/{id}/events", CLAIM_ID))
                    .andExpect(status().isNotFound());
        }
    }
}
