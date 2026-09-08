package com.hines.claims.common.error;

import com.hines.claims.claim.ClaimNotFoundException;
import com.hines.claims.claim.IllegalClaimTransitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns exceptions into RFC 9457 problem details, in one place.
 *
 * <p>Two things this buys us. Every error in the API has the same shape, so a
 * client writes one parser. And no controller needs a try/catch, so each one
 * states its happy path and nothing else.
 *
 * <p>The catch-all at the bottom exists so an unanticipated exception can never
 * reach a client as a stack trace. Class names, library versions, and sometimes
 * SQL fragments leak that way - a genuine finding in a regulated environment, not
 * a style note. The detail goes to the log; the client gets a reference id.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String PROBLEM_BASE = "https://claims.hines.dev/problems/";

    /** Unknown claim id -> 404. */
    @ExceptionHandler(ClaimNotFoundException.class)
    ProblemDetail handleNotFound(ClaimNotFoundException e) {
        ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "Claim not found", e.getMessage(), "claim-not-found");
        problem.setProperty("claimId", e.getClaimId());
        return problem;
    }

    /**
     * Illegal lifecycle transition -> 409, not 400.
     *
     * <p>The request is well-formed; it conflicts with the claim's current state.
     * A 400 would tell the client its request was malformed, which is wrong and
     * sends them looking in the wrong place.
     */
    @ExceptionHandler(IllegalClaimTransitionException.class)
    ProblemDetail handleIllegalTransition(IllegalClaimTransitionException e) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT,
                "Illegal claim transition", e.getMessage(), "illegal-transition");
        problem.setProperty("claimId", e.getClaimId());
        problem.setProperty("from", e.getFrom());
        problem.setProperty("to", e.getTo());
        return problem;
    }

    /**
     * Concurrent modification -> 409.
     *
     * <p>Raised by the {@code @Version} column when another transaction changed
     * the row since it was read. The client's own request was valid, so this is a
     * conflict and is safe to retry after re-reading.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail handleConcurrentModification(ObjectOptimisticLockingFailureException e) {
        log.info("Optimistic lock conflict: {}", e.getMessage());
        return problem(HttpStatus.CONFLICT,
                "Concurrent modification",
                "This claim was modified by someone else. Re-read it and retry.",
                "concurrent-modification");
    }

    /** Bean Validation failure on a request DTO -> 400, with per-field messages. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidationFailure(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(error ->
                fieldErrors.merge(error.getField(), defaultMessage(error.getDefaultMessage()),
                        (first, second) -> first + "; " + second));

        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST,
                "Validation failed", "One or more fields are invalid.", "validation-failed");
        problem.setProperty("errors", fieldErrors);
        return problem;
    }

    /**
     * Domain invariant violated -> 400.
     *
     * <p>Reached when the entity rejects something the DTO constraints did not
     * catch. Both layers guard the same rules on purpose (Lesson 5): the DTO for a
     * precise message, the domain for a guarantee that holds for every caller.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleInvalidArgument(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", e.getMessage(), "invalid-request");
    }

    /**
     * Unparseable body -> 400.
     *
     * <p>Covers malformed JSON and unknown enum values. The exception message can
     * echo the raw payload, so it is logged rather than returned.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleUnreadableBody(HttpMessageNotReadableException e) {
        log.debug("Unreadable request body", e);
        return problem(HttpStatus.BAD_REQUEST,
                "Malformed request",
                "The request body could not be parsed. Check JSON syntax and field types.",
                "malformed-request");
    }

    /**
     * A path or query parameter that will not convert -> 400.
     *
     * <p>{@code /api/claims/not-a-uuid} fails before the controller method runs.
     * Without this handler it falls through to the catch-all and surfaces as a
     * 500, telling the client the server is broken when in fact their URL was.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String required = e.getRequiredType() == null ? "the expected type" : e.getRequiredType().getSimpleName();

        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST,
                "Invalid parameter",
                "Parameter '%s' is not a valid %s.".formatted(e.getName(), required),
                "invalid-parameter");
        problem.setProperty("parameter", e.getName());
        return problem;
    }

    /**
     * Spring's own web exceptions already carry the correct status - unknown route
     * (404), wrong HTTP method (405), unsupported content type (415). Passing them
     * through preserves that.
     *
     * <p>Without this, the catch-all below would swallow them and report 500 for
     * an ordinary typo in a URL, which is both wrong and alarming in monitoring.
     */
    @ExceptionHandler(ErrorResponseException.class)
    ProblemDetail handleSpringWebError(ErrorResponseException e) {
        return e.getBody();
    }

    /**
     * Anything unanticipated -> 500, with nothing revealing in the body.
     *
     * <p>The full stack trace goes to the log against a generated reference the
     * client also receives, so a support request can be traced to the exact log
     * entry without ever exposing internals over HTTP.
     */
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception e) {
        String reference = Long.toHexString(System.nanoTime());
        log.error("Unhandled exception [ref={}]", reference, e);

        ProblemDetail problem = problem(HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal server error",
                "Something went wrong. Quote the reference when reporting this.",
                "internal-error");
        problem.setProperty("reference", reference);
        return problem;
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail, String type) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(PROBLEM_BASE + type));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    private static String defaultMessage(String message) {
        return message == null ? "is invalid" : message;
    }
}
