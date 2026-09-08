package com.hines.claims.idempotency;

import com.hines.claims.TestcontainersConfiguration;
import com.hines.claims.claim.ClaimRepository;
import com.hines.claims.claim.ClaimService;
import com.hines.claims.claim.ClaimType;
import com.hines.claims.claim.dto.ClaimResponse;
import com.hines.claims.claim.dto.SubmitClaimRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

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
 * Idempotent submission, against a real PostgreSQL instance.
 *
 * <p>The scenario being defended against: a client POSTs a claim, the response is
 * lost to a timeout, and the client retries. It cannot know whether the first
 * attempt succeeded, so retrying is the only reasonable thing it can do - and
 * without protection that produces two claims and, later, two payouts.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("idempotent submission")
class IdempotentSubmissionIntegrationTest {

    @Autowired
    private ClaimService claimService;

    @Autowired
    private ClaimRepository claimRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRepository;

    private static SubmitClaimRequest aRequest(String policyNumber) {
        return new SubmitClaimRequest(policyNumber, "Jane Doe", ClaimType.MEDICAL,
                new BigDecimal("750.00"), LocalDate.now().minusDays(2));
    }

    @Test
    void a_retry_with_the_same_key_returns_the_original_claim() {
        String key = "key-" + UUID.randomUUID();
        SubmitClaimRequest request = aRequest("POL-RETRY");

        ClaimResponse first = claimService.submit(request, key);
        ClaimResponse retry = claimService.submit(request, key);

        assertThat(retry.id())
                .describedAs("the retry must return the original claim, not a new one")
                .isEqualTo(first.id());
        assertThat(retry.submittedAt())
                .describedAs("a replay returns the stored claim, not a freshly timestamped one")
                .isEqualTo(first.submittedAt());
        assertThat(countOf("POL-RETRY")).isEqualTo(1);
    }

    @Test
    void repeated_retries_still_produce_only_one_claim() {
        String key = "key-" + UUID.randomUUID();
        SubmitClaimRequest request = aRequest("POL-MANY-RETRIES");

        ClaimResponse first = claimService.submit(request, key);
        for (int i = 0; i < 5; i++) {
            assertThat(claimService.submit(request, key).id()).isEqualTo(first.id());
        }

        assertThat(countOf("POL-MANY-RETRIES")).isEqualTo(1);
    }

    @Test
    void different_keys_create_different_claims() {
        SubmitClaimRequest request = aRequest("POL-DISTINCT");

        ClaimResponse one = claimService.submit(request, "key-" + UUID.randomUUID());
        ClaimResponse two = claimService.submit(request, "key-" + UUID.randomUUID());

        assertThat(one.id())
                .describedAs("an identical body under a new key is a genuinely new claim")
                .isNotEqualTo(two.id());
        assertThat(countOf("POL-DISTINCT")).isEqualTo(2);
    }

    @Test
    void submitting_without_a_key_is_allowed_and_unprotected() {
        SubmitClaimRequest request = aRequest("POL-NO-KEY");

        ClaimResponse one = claimService.submit(request, null);
        ClaimResponse two = claimService.submit(request, null);

        assertThat(one.id()).isNotEqualTo(two.id());
        assertThat(countOf("POL-NO-KEY"))
                .describedAs("no key means no duplicate protection - that is the trade-off")
                .isEqualTo(2);
    }

    /**
     * The client bug this guards against: one key generated and then reused across
     * several distinct requests, usually because it was hoisted out of a loop.
     *
     * <p>Replaying the first result would silently discard the second request and
     * hand back a claim for something else entirely.
     */
    @Test
    void reusing_a_key_with_a_different_body_is_rejected() {
        String key = "key-" + UUID.randomUUID();

        claimService.submit(aRequest("POL-FIRST"), key);

        assertThatExceptionOfType(IdempotencyKeyConflictException.class)
                .isThrownBy(() -> claimService.submit(aRequest("POL-SECOND"), key))
                .satisfies(e -> assertThat(e.getIdempotencyKey()).isEqualTo(key));

        assertThat(countOf("POL-SECOND"))
                .describedAs("the rejected request must not have created anything")
                .isZero();
    }

    /**
     * The hard case: several requests carrying the same key arrive at once, so all
     * of them find no prior record and all proceed.
     *
     * <p>The primary key on {@code idempotency_keys} decides it. Exactly one
     * transaction commits; the rest fail and roll back <em>including their
     * claims</em>. What must never happen is two claims for one key.
     */
    @Test
    void simultaneous_requests_with_one_key_create_exactly_one_claim() throws Exception {
        String key = "key-" + UUID.randomUUID();
        SubmitClaimRequest request = aRequest("POL-CONCURRENT");

        int threads = 6;
        AtomicInteger created = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        startGate.await();
                        claimService.submit(request, key);
                        created.incrementAndGet();
                    } catch (DataIntegrityViolationException e) {
                        // Lost the insert race on the primary key.
                        rejected.incrementAndGet();
                    } catch (Exception e) {
                        unexpected.incrementAndGet();
                        System.out.println("UNEXPECTED: " + e.getClass().getName() + " - " + e.getMessage());
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

        assertThat(created.get() + rejected.get()).isEqualTo(threads);

        // The invariant that actually matters - not how many succeeded, but that
        // the database holds exactly one claim for this key.
        assertThat(countOf("POL-CONCURRENT"))
                .describedAs("one key must never yield two claims (returned-without-error=%d, rejected=%d)",
                        created.get(), rejected.get())
                .isEqualTo(1);

        assertThat(idempotencyRepository.findById(key))
                .describedAs("the surviving key must point at the surviving claim")
                .isPresent();
    }

    @Test
    void the_recorded_key_points_at_the_claim_it_created() {
        String key = "key-" + UUID.randomUUID();

        ClaimResponse created = claimService.submit(aRequest("POL-RECORD"), key);

        IdempotencyRecord record = idempotencyRepository.findById(key).orElseThrow();
        assertThat(record.getClaimId()).isEqualTo(created.id());
        assertThat(record.getEndpoint()).isEqualTo("POST /api/claims");
        assertThat(record.getRequestHash())
                .describedAs("SHA-256 hex")
                .hasSize(64);
        assertThat(record.getCreatedAt()).isNotNull();
    }

    private long countOf(String policyNumber) {
        return claimRepository.findAll().stream()
                .filter(claim -> policyNumber.equals(claim.getPolicyNumber()))
                .count();
    }
}
