package com.hines.claims.claim;

import com.hines.claims.TestcontainersConfiguration;
import com.hines.claims.claim.dto.ClaimResponse;
import com.hines.claims.claim.dto.SubmitClaimRequest;
import com.hines.claims.common.dto.PageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Filtering and pagination, against a real PostgreSQL instance.
 *
 * <p>Each test tags its data with a unique policy number and filters on it, so
 * the tests stay independent despite sharing one container - and despite the
 * audit and ledger tables being append-only, which rules out cleaning between
 * tests.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("claim search")
class ClaimSearchIntegrationTest {

    @Autowired
    private ClaimService claimService;

    /** Unique per test method, so one test's rows cannot affect another's. */
    private String policy;

    @BeforeEach
    void assignUniquePolicy() {
        policy = "POL-SEARCH-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private ClaimResponse submit(BigDecimal amount) {
        return claimService.submit(new SubmitClaimRequest(
                policy, "Jane Doe", ClaimType.MEDICAL, amount,
                LocalDate.now().minusDays(3)), null);
    }

    private PageResponse<ClaimResponse> searchThisPolicy(ClaimStatus status, Pageable pageable) {
        return claimService.search(new ClaimSearchCriteria(status, policy, null, null), pageable);
    }

    @Test
    void an_unfiltered_search_still_pages() {
        submit(new BigDecimal("100.00"));
        submit(new BigDecimal("200.00"));

        PageResponse<ClaimResponse> page = searchThisPolicy(null, PageRequest.of(0, 10));

        assertThat(page.content()).hasSize(2);
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(1);
        assertThat(page.first()).isTrue();
        assertThat(page.last()).isTrue();
    }

    @Test
    void filtering_by_status_returns_only_that_status() {
        submit(new BigDecimal("100.00"));
        ClaimResponse toReview = submit(new BigDecimal("200.00"));
        claimService.review(toReview.id(), toReview.version());

        PageResponse<ClaimResponse> underReview =
                searchThisPolicy(ClaimStatus.UNDER_REVIEW, PageRequest.of(0, 10));

        assertThat(underReview.content()).hasSize(1);
        assertThat(underReview.content().getFirst().id()).isEqualTo(toReview.id());

        PageResponse<ClaimResponse> submitted =
                searchThisPolicy(ClaimStatus.SUBMITTED, PageRequest.of(0, 10));

        assertThat(submitted.content()).hasSize(1);
        assertThat(submitted.content().getFirst().status()).isEqualTo(ClaimStatus.SUBMITTED);
    }

    @Test
    void filtering_by_policy_number_excludes_other_policies() {
        submit(new BigDecimal("100.00"));

        claimService.submit(new SubmitClaimRequest(
                "POL-SOMEONE-ELSE", "Other Person", ClaimType.DENTAL,
                new BigDecimal("50.00"), LocalDate.now().minusDays(1)), null);

        PageResponse<ClaimResponse> mine = searchThisPolicy(null, PageRequest.of(0, 10));

        assertThat(mine.content())
                .describedAs("one member's claims must not leak into another's list")
                .hasSize(1)
                .allSatisfy(claim -> assertThat(claim.policyNumber()).isEqualTo(policy));
    }

    @Test
    void a_time_range_is_inclusive_of_from_and_exclusive_of_to() {
        ClaimResponse claim = submit(new BigDecimal("100.00"));
        Instant submittedAt = claim.submittedAt();

        // from == submittedAt: included.
        assertThat(claimService.search(
                new ClaimSearchCriteria(null, policy, submittedAt, null), PageRequest.of(0, 10))
                .content())
                .describedAs("the lower bound is inclusive")
                .hasSize(1);

        // to == submittedAt: excluded.
        assertThat(claimService.search(
                new ClaimSearchCriteria(null, policy, null, submittedAt), PageRequest.of(0, 10))
                .content())
                .describedAs("the upper bound is exclusive, so ranges tile without overlap")
                .isEmpty();

        // A range that ends just after it: included.
        assertThat(claimService.search(
                new ClaimSearchCriteria(null, policy, null, submittedAt.plusMillis(1)),
                PageRequest.of(0, 10)).content())
                .hasSize(1);
    }

    @Test
    void filters_combine_with_and_not_or() {
        ClaimResponse reviewed = submit(new BigDecimal("100.00"));
        claimService.review(reviewed.id(), reviewed.version());
        submit(new BigDecimal("200.00"));

        // Right status, wrong policy - must match nothing.
        assertThat(claimService.search(
                new ClaimSearchCriteria(ClaimStatus.UNDER_REVIEW, "POL-NOT-THIS-ONE", null, null),
                PageRequest.of(0, 10)).content())
                .isEmpty();
    }

    @Test
    void results_are_paged_and_the_pages_do_not_overlap() {
        for (int i = 0; i < 5; i++) {
            submit(new BigDecimal("10" + i + ".00"));
        }

        PageResponse<ClaimResponse> first = searchThisPolicy(null, PageRequest.of(0, 2));
        PageResponse<ClaimResponse> second = searchThisPolicy(null, PageRequest.of(1, 2));
        PageResponse<ClaimResponse> third = searchThisPolicy(null, PageRequest.of(2, 2));

        assertThat(first.content()).hasSize(2);
        assertThat(second.content()).hasSize(2);
        assertThat(third.content()).hasSize(1);

        assertThat(first.totalElements()).isEqualTo(5);
        assertThat(first.totalPages()).isEqualTo(3);
        assertThat(first.first()).isTrue();
        assertThat(third.last()).isTrue();

        assertThat(first.content()).doesNotContainAnyElementsOf(second.content());
        assertThat(second.content()).doesNotContainAnyElementsOf(third.content());
    }

    @Test
    void the_default_sort_is_newest_submission_first() {
        submit(new BigDecimal("100.00"));
        submit(new BigDecimal("200.00"));
        submit(new BigDecimal("300.00"));

        // Unsorted Pageable - the service must supply the default.
        PageResponse<ClaimResponse> page = searchThisPolicy(null, PageRequest.of(0, 10));

        assertThat(page.content())
                .describedAs("without a deterministic order, pages can repeat or skip rows")
                .isSortedAccordingTo((a, b) -> b.submittedAt().compareTo(a.submittedAt()));
    }

    @Test
    void an_explicit_sort_is_respected() {
        submit(new BigDecimal("300.00"));
        submit(new BigDecimal("100.00"));
        submit(new BigDecimal("200.00"));

        PageResponse<ClaimResponse> page = searchThisPolicy(null,
                PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "claimedAmount")));

        assertThat(page.content()).extracting(ClaimResponse::claimedAmount)
                .containsExactly(
                        new BigDecimal("100.00"),
                        new BigDecimal("200.00"),
                        new BigDecimal("300.00"));
    }

    /**
     * A caller asking for a million rows gets a hundred.
     *
     * <p>Not primarily a malice defence - it is far more often a client that
     * assumed it could fetch everything, and would otherwise take the service down
     * by accident.
     */
    @Test
    void an_oversized_page_request_is_capped() {
        submit(new BigDecimal("100.00"));

        PageResponse<ClaimResponse> page = searchThisPolicy(null, PageRequest.of(0, 1_000_000));

        assertThat(page.size())
                .describedAs("page size must be bounded regardless of what was asked for")
                .isEqualTo(100);
    }

    @Test
    void a_page_beyond_the_end_is_empty_rather_than_an_error() {
        submit(new BigDecimal("100.00"));

        PageResponse<ClaimResponse> page = searchThisPolicy(null, PageRequest.of(50, 10));

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.last()).isTrue();
    }

    @Test
    void a_filter_matching_nothing_returns_an_empty_page_not_a_404() {
        assertThat(claimService.search(
                new ClaimSearchCriteria(null, "POL-DOES-NOT-EXIST", null, null),
                PageRequest.of(0, 10)))
                .satisfies(page -> {
                    assertThat(page.content()).isEmpty();
                    assertThat(page.totalElements()).isZero();
                    // "No claims match" is a valid answer to a valid question.
                    // 404 would mean the endpoint does not exist.
                });
    }
}
