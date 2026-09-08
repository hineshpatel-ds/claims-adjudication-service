package com.hines.claims.claim;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the WHERE clause for a claim search from whichever filters were given.
 *
 * <p><strong>Why not a single {@code @Query}?</strong> The usual shortcut is:
 *
 * <pre>
 *   WHERE (:status IS NULL OR c.status = :status)
 *     AND (:policyNumber IS NULL OR c.policyNumber = :policyNumber)
 * </pre>
 *
 * <p>It reads well and it works, but every request produces the same SQL
 * regardless of which filters were supplied. The planner sees a query whose
 * selectivity it cannot know in advance, and with a cached generic plan it can
 * settle on a sequential scan even when an index would serve - so the indexes
 * from V1 stop being used exactly when the table gets big enough to need them.
 *
 * <p>Specifications compose only the predicates actually present, so a search on
 * {@code policyNumber} alone emits {@code WHERE policy_number = ?} and uses
 * {@code idx_claims_policy_number}. The SQL matches the request.
 *
 * <p>A private constructor: this is a namespace for factory methods, not a type
 * anyone should instantiate.
 */
final class ClaimSpecifications {

    private ClaimSpecifications() {
    }

    /**
     * Every supplied filter, ANDed together.
     *
     * <p>Returns {@code null} when nothing was supplied, which Spring Data reads
     * as "no restriction" - an unfiltered, still-paginated list.
     */
    static Specification<Claim> matching(ClaimSearchCriteria criteria) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (criteria.status() != null) {
                predicates.add(builder.equal(root.get("status"), criteria.status()));
            }

            if (criteria.policyNumber() != null && !criteria.policyNumber().isBlank()) {
                predicates.add(builder.equal(root.get("policyNumber"), criteria.policyNumber()));
            }

            // Half-open: from inclusive, to exclusive. See ClaimSearchCriteria.
            if (criteria.from() != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("submittedAt"), criteria.from()));
            }

            if (criteria.to() != null) {
                predicates.add(builder.lessThan(root.get("submittedAt"), criteria.to()));
            }

            if (predicates.isEmpty()) {
                return null;
            }

            return builder.and(predicates.toArray(new Predicate[0]));
        };
    }
}
