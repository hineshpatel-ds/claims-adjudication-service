package com.hines.claims.claim;

import com.hines.claims.TestcontainersConfiguration;
import com.hines.claims.claim.dto.ClaimResponse;
import com.hines.claims.claim.dto.SubmitClaimRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Concurrency behaviour, against a real PostgreSQL instance.
 *
 * <p>This is the test that makes {@code @Version} more than an annotation you can
 * describe. Without optimistic locking, two adjusters approving the same claim at
 * the same moment both "succeed" and one decision disappears silently - no error,
 * no log, no trace. In a payout system that is money decided twice and recorded
 * once.
 *
 * <p>It cannot be written against H2 or a mock. The guarantee being tested is the
 * database's atomic conditional UPDATE - {@code WHERE id = ? AND version = ?} -
 * so the test needs a real database and real concurrent transactions. That is the
 * concrete argument for Testcontainers over an in-memory substitute.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("concurrent adjudication")
class ClaimConcurrencyIntegrationTest {

    @Autowired
    private ClaimService claimService;

    @Autowired
    private ClaimRepository claimRepository;

    private static final int CONTENDING_THREADS = 8;

    private ClaimResponse aClaimUnderReview() {
        ClaimResponse submitted = claimService.submit(new SubmitClaimRequest(
                "POL-" + UUID.randomUUID().toString().substring(0, 8),
                "Jane Doe",
                ClaimType.MEDICAL,
                new BigDecimal("1000.00"),
                LocalDate.now().minusDays(3)));

        return claimService.review(submitted.id(), submitted.version());
    }

    /**
     * The real race: several threads read the same version, then all try to write.
     *
     * <p>Exactly one must win. The losers fail loudly rather than silently
     * overwriting, which is the entire point - a conflict the caller can see and
     * retry is a correct outcome; a lost decision is not.
     */
    @Test
    void only_one_of_many_simultaneous_approvals_can_succeed() throws Exception {
        ClaimResponse claim = aClaimUnderReview();
        UUID id = claim.id();
        long staleVersion = claim.version();

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        // Every thread blocks here, so they are released together and genuinely
        // contend rather than running one after another.
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(CONTENDING_THREADS);

        ExecutorService pool = Executors.newFixedThreadPool(CONTENDING_THREADS);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < CONTENDING_THREADS; i++) {
                final BigDecimal amount = new BigDecimal(100 + i + ".00");

                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        startGate.await();
                        claimService.approve(id, staleVersion, amount);
                        succeeded.incrementAndGet();
                    } catch (ObjectOptimisticLockingFailureException e) {
                        // Lost the write race: another transaction committed first.
                        conflicted.incrementAndGet();
                    } catch (StaleClaimVersionException e) {
                        // Lost before even starting: the winner had already committed.
                        conflicted.incrementAndGet();
                    } catch (IllegalClaimTransitionException e) {
                        // The winner already moved it out of UNDER_REVIEW.
                        conflicted.incrementAndGet();
                    } catch (Exception e) {
                        unexpected.incrementAndGet();
                    }
                    return null;
                }));
            }

            ready.await(30, TimeUnit.SECONDS);
            startGate.countDown();

            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(unexpected.get())
                .describedAs("no thread should fail for a reason other than losing the race")
                .isZero();

        assertThat(succeeded.get())
                .describedAs("exactly one approval may win")
                .isEqualTo(1);

        assertThat(conflicted.get())
                .describedAs("every other thread must be told it lost, not silently ignored")
                .isEqualTo(CONTENDING_THREADS - 1);

        // And the stored claim reflects exactly one approval.
        Claim stored = claimRepository.findById(id).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ClaimStatus.APPROVED);
        assertThat(stored.getApprovedAmount()).isNotNull();
        assertThat(stored.getVersion())
                .describedAs("one successful write past UNDER_REVIEW")
                .isEqualTo(2L);
    }

    /**
     * The slow, common case: no race at all, just an out-of-date screen.
     *
     * <p>Deterministic, unlike the test above - it does not depend on thread
     * timing, so it is the one that will still be meaningful on a single-core CI
     * runner.
     */
    @Test
    void approving_with_an_out_of_date_version_is_refused() {
        ClaimResponse claim = aClaimUnderReview();
        long currentVersion = claim.version();

        assertThatExceptionOfType(StaleClaimVersionException.class)
                .isThrownBy(() -> claimService.approve(claim.id(), currentVersion - 1, new BigDecimal("50.00")))
                .satisfies(e -> {
                    assertThat(e.getExpectedVersion()).isEqualTo(currentVersion - 1);
                    assertThat(e.getActualVersion()).isEqualTo(currentVersion);
                });

        Claim stored = claimRepository.findById(claim.id()).orElseThrow();
        assertThat(stored.getStatus())
                .describedAs("a refused request must not have changed anything")
                .isEqualTo(ClaimStatus.UNDER_REVIEW);
    }

    /**
     * The version increments on every successful write, which is what makes the
     * whole scheme work - and what a client must send back.
     */
    @Test
    void each_successful_transition_advances_the_version() {
        ClaimResponse submitted = claimService.submit(new SubmitClaimRequest(
                "POL-VERSIONS", "Jane Doe", ClaimType.DENTAL,
                new BigDecimal("300.00"), LocalDate.now().minusDays(1)));
        assertThat(submitted.version()).isZero();

        ClaimResponse reviewed = claimService.review(submitted.id(), submitted.version());
        assertThat(reviewed.status()).isEqualTo(ClaimStatus.UNDER_REVIEW);

        ClaimResponse approved = claimService.approve(
                reviewed.id(), reviewed.version(), new BigDecimal("250.00"));
        assertThat(approved.status()).isEqualTo(ClaimStatus.APPROVED);
        assertThat(approved.approvedAmount()).isEqualByComparingTo("250.00");

        Claim stored = claimRepository.findById(submitted.id()).orElseThrow();
        assertThat(stored.getVersion()).isEqualTo(2L);
    }

    /**
     * Persistence round-trip: the domain rules still hold after a real write and
     * read, and the money value is not mangled by NUMERIC(12,2).
     */
    @Test
    void a_rejected_claim_persists_its_reason_and_cannot_then_be_approved() {
        ClaimResponse underReview = aClaimUnderReview();

        ClaimResponse rejected = claimService.reject(
                underReview.id(), underReview.version(), "Treatment not covered under this policy");

        assertThat(rejected.status()).isEqualTo(ClaimStatus.REJECTED);
        assertThat(rejected.rejectionReason()).isEqualTo("Treatment not covered under this policy");
        assertThat(rejected.allowedNextStates()).isEmpty();

        assertThatExceptionOfType(IllegalClaimTransitionException.class)
                .isThrownBy(() -> claimService.approve(
                        rejected.id(), rejected.version(), new BigDecimal("10.00")));
    }
}
