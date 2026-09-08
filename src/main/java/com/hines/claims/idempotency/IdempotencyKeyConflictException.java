package com.hines.claims.idempotency;

/**
 * Thrown when an idempotency key is reused with a different request body.
 *
 * <p>Almost always a client bug: a key generated once and then reused across
 * several distinct requests, typically because it was hoisted out of a loop.
 *
 * <p>Returning the first result would be worse than failing. The caller asked for
 * something new, so answering with an unrelated earlier claim silently discards
 * their request and hands back a record for something else entirely - the kind of
 * defect that surfaces days later as "why does this claim have the wrong amount".
 */
public class IdempotencyKeyConflictException extends RuntimeException {

    private final String idempotencyKey;

    public IdempotencyKeyConflictException(String idempotencyKey) {
        super("Idempotency key '%s' was already used for a different request".formatted(idempotencyKey));
        this.idempotencyKey = idempotencyKey;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
