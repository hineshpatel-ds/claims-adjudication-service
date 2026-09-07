package com.hines.claims.claim;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Persistence for {@link Claim}.
 *
 * <p>An interface with no implementation. Spring Data generates a proxy at
 * startup that supplies {@code save}, {@code findById}, {@code delete} and the
 * rest from {@link JpaRepository}, and derives queries from method names.
 *
 * <p>{@code @Repository} is optional here - Spring Data registers the bean
 * regardless - but it is kept for the exception translation it enables and
 * because it states the role of the interface to a reader.
 *
 * <p>Query methods are added as endpoints need them, not in advance. An unused
 * finder is still code that must be read and maintained.
 */
@Repository
public interface ClaimRepository extends JpaRepository<Claim, UUID> {
}
