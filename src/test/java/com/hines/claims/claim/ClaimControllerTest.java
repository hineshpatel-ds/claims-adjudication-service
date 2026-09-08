package com.hines.claims.claim;

import com.hines.claims.claim.dto.ClaimResponse;
import com.hines.claims.claim.dto.SubmitClaimRequest;
import com.hines.claims.common.error.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract for the claims endpoints.
 *
 * <p>A slice test: routing, JSON binding, Bean Validation, and the exception
 * handler all run for real, while the service is mocked. Fast, and it isolates
 * failures to the web layer - if this passes and an integration test fails, the
 * problem is below the controller.
 *
 * <p>Failure cases outnumber happy paths here on purpose. Testing only the 201 is
 * the most common gap in student projects, and status-code behaviour is exactly
 * what an API's clients depend on.
 */
@WebMvcTest(ClaimController.class)
@Import(GlobalExceptionHandler.class)
class ClaimControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ClaimService claimService;

    private static final UUID CLAIM_ID = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");

    private static SubmitClaimRequest validRequest() {
        return new SubmitClaimRequest("POL-123456", "Jane Doe", ClaimType.MEDICAL,
                new BigDecimal("1200.00"), LocalDate.of(2026, 8, 30));
    }

    private static ClaimResponse responseFor(SubmitClaimRequest request) {
        return new ClaimResponse(CLAIM_ID, request.policyNumber(), request.claimantName(),
                request.claimType(), request.claimedAmount(), null,
                ClaimStatus.SUBMITTED, Set.of(ClaimStatus.UNDER_REVIEW),
                request.incidentDate(), Instant.parse("2026-09-07T12:00:00Z"),
                null, null, null, 0L);
    }

    @Nested
    @DisplayName("POST /api/claims")
    class Submit {

        @Test
        void a_valid_submission_returns_201_with_a_location_header() throws Exception {
            SubmitClaimRequest request = validRequest();
            given(claimService.submit(any())).willReturn(responseFor(request));

            mockMvc.perform(post("/api/claims")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location",
                            "http://localhost/api/claims/" + CLAIM_ID))
                    .andExpect(jsonPath("$.id").value(CLAIM_ID.toString()))
                    .andExpect(jsonPath("$.status").value("SUBMITTED"))
                    .andExpect(jsonPath("$.claimedAmount").value(1200.00))
                    .andExpect(jsonPath("$.allowedNextStates[0]").value("UNDER_REVIEW"));
        }

        @Test
        void a_negative_amount_is_rejected_with_a_field_message() throws Exception {
            String body = """
                    {
                      "policyNumber": "POL-1",
                      "claimantName": "Jane Doe",
                      "claimType": "MEDICAL",
                      "claimedAmount": -50.00,
                      "incidentDate": "2026-08-30"
                    }
                    """;

            mockMvc.perform(post("/api/claims")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.title").value("Validation failed"))
                    .andExpect(jsonPath("$.errors.claimedAmount").exists());
        }

        @Test
        void a_missing_required_field_is_rejected() throws Exception {
            String body = """
                    {
                      "claimantName": "Jane Doe",
                      "claimType": "MEDICAL",
                      "claimedAmount": 100.00,
                      "incidentDate": "2026-08-30"
                    }
                    """;

            mockMvc.perform(post("/api/claims")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.policyNumber").exists());
        }

        @Test
        void a_future_incident_date_is_rejected() throws Exception {
            String body = """
                    {
                      "policyNumber": "POL-1",
                      "claimantName": "Jane Doe",
                      "claimType": "MEDICAL",
                      "claimedAmount": 100.00,
                      "incidentDate": "2099-01-01"
                    }
                    """;

            mockMvc.perform(post("/api/claims")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.incidentDate").exists());
        }

        @Test
        void too_many_decimal_places_on_money_is_rejected() throws Exception {
            String body = """
                    {
                      "policyNumber": "POL-1",
                      "claimantName": "Jane Doe",
                      "claimType": "MEDICAL",
                      "claimedAmount": 100.005,
                      "incidentDate": "2026-08-30"
                    }
                    """;

            mockMvc.perform(post("/api/claims")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.claimedAmount").exists());
        }

        @Test
        void an_unknown_claim_type_is_rejected_as_malformed() throws Exception {
            String body = """
                    {
                      "policyNumber": "POL-1",
                      "claimantName": "Jane Doe",
                      "claimType": "TELEPATHY",
                      "claimedAmount": 100.00,
                      "incidentDate": "2026-08-30"
                    }
                    """;

            mockMvc.perform(post("/api/claims")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Malformed request"));
        }

        @Test
        void malformed_json_is_rejected_without_leaking_internals() throws Exception {
            mockMvc.perform(post("/api/claims")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{ not json"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(
                            "The request body could not be parsed. Check JSON syntax and field types."));
        }

        /**
         * The mass-assignment guard from ADR-0006. A client sending privileged
         * fields must not be able to set them - the DTO has nowhere to put them,
         * so they are ignored rather than applied.
         */
        @Test
        void a_client_cannot_set_status_or_approved_amount() throws Exception {
            SubmitClaimRequest request = validRequest();
            given(claimService.submit(any())).willReturn(responseFor(request));

            String body = """
                    {
                      "policyNumber": "POL-123456",
                      "claimantName": "Jane Doe",
                      "claimType": "MEDICAL",
                      "claimedAmount": 1200.00,
                      "incidentDate": "2026-08-30",
                      "status": "PAID",
                      "approvedAmount": 999999.00
                    }
                    """;

            mockMvc.perform(post("/api/claims")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("SUBMITTED"))
                    .andExpect(jsonPath("$.approvedAmount").doesNotExist());
        }
    }

    @Nested
    @DisplayName("GET /api/claims/{id}")
    class FindById {

        @Test
        void an_existing_claim_returns_200() throws Exception {
            given(claimService.findById(CLAIM_ID)).willReturn(responseFor(validRequest()));

            mockMvc.perform(get("/api/claims/{id}", CLAIM_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(CLAIM_ID.toString()))
                    .andExpect(jsonPath("$.policyNumber").value("POL-123456"));
        }

        @Test
        void an_unknown_id_returns_404_as_a_problem_detail() throws Exception {
            willThrow(new ClaimNotFoundException(CLAIM_ID))
                    .given(claimService).findById(CLAIM_ID);

            mockMvc.perform(get("/api/claims/{id}", CLAIM_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.title").value("Claim not found"))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.claimId").value(CLAIM_ID.toString()));
        }

        @Test
        void an_illegal_transition_surfaces_as_409_with_both_states() throws Exception {
            willThrow(new IllegalClaimTransitionException(CLAIM_ID, ClaimStatus.REJECTED, ClaimStatus.PAID))
                    .given(claimService).findById(CLAIM_ID);

            mockMvc.perform(get("/api/claims/{id}", CLAIM_ID))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.title").value("Illegal claim transition"))
                    .andExpect(jsonPath("$.from").value("REJECTED"))
                    .andExpect(jsonPath("$.to").value("PAID"));
        }

        @Test
        void a_malformed_uuid_is_a_bad_request_not_a_500() throws Exception {
            mockMvc.perform(get("/api/claims/{id}", "not-a-uuid"))
                    .andExpect(status().isBadRequest());
        }
    }

    /**
     * Regression tests for a real defect: the catch-all handler was swallowing
     * Spring's own web exceptions and reporting 500 for them. An unknown route
     * returned "Internal server error", which is wrong for the caller and, in
     * production, wakes someone up over a typo in a URL.
     *
     * <p>No unit test caught it - only calling the running service did. These exist
     * so it cannot come back.
     */
    @Nested
    @DisplayName("standard web errors keep their own status codes")
    class StandardWebErrors {

        @Test
        void an_unknown_route_returns_404_not_500() throws Exception {
            mockMvc.perform(get("/api/nonexistent"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.title").value("Endpoint not found"));
        }

        @Test
        void the_wrong_http_method_returns_405_not_500() throws Exception {
            mockMvc.perform(delete("/api/claims/{id}", CLAIM_ID))
                    .andExpect(status().isMethodNotAllowed());
        }

        @Test
        void an_unsupported_content_type_returns_415_not_500() throws Exception {
            mockMvc.perform(post("/api/claims")
                            .contentType(MediaType.TEXT_PLAIN)
                            .content("not json"))
                    .andExpect(status().isUnsupportedMediaType());
        }
    }
}
