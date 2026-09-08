package com.hines.claims.common.error;

import com.hines.claims.claim.ClaimNotFoundException;
import com.hines.claims.claim.IllegalClaimTransitionException;
import com.hines.claims.claim.StaleClaimVersionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns exceptions into RFC 9457 problem details, in one place.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} deliberately. That base class
 * already maps every standard Spring MVC exception to the right status - unknown
 * route to 404, wrong method to 405, unsupported media type to 415, and a dozen
 * more. Without it the catch-all at the bottom swallows all of them and reports
 * 500, so a mistyped URL looks like a server fault.
 *
 * <p>That is not hypothetical: this service returned 500 for an unknown route
 * until this class was changed, and no unit test caught it - only calling the
 * running service on a wrong path did. In production that means a bot probing for
 * {@code /wp-admin} raises the 500-rate alarm and pages someone.
 *
 * <p>The division of labour:
 * <ul>
 *   <li>base class - standard Spring MVC exceptions</li>
 *   <li>{@code @ExceptionHandler} methods - our domain exceptions</li>
 *   <li>overrides - standard exceptions we want a better body for</li>
 *   <li>catch-all - anything unanticipated, with nothing revealing in the body</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String PROBLEM_BASE = "https://claims.hines.dev/problems/";

    // ---------------------------------------------------------------------
    // Domain exceptions
    // ---------------------------------------------------------------------

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
     * A 400 would send the caller looking for a payload bug that does not exist.
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
     * Stale read -> 409.
     *
     * <p>The caller acted on a version of the claim that is no longer current -
     * they loaded it, someone else changed it, and they decided against what their
     * screen still showed. Returning both versions lets a UI say precisely what
     * happened instead of "please try again".
     */
    @ExceptionHandler(StaleClaimVersionException.class)
    ProblemDetail handleStaleVersion(StaleClaimVersionException e) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT,
                "Stale claim version",
                "This claim has changed since you loaded it. Re-read it and retry.",
                "stale-version");
        problem.setProperty("claimId", e.getClaimId());
        problem.setProperty("expectedVersion", e.getExpectedVersion());
        problem.setProperty("actualVersion", e.getActualVersion());
        return problem;
    }

    /**
     * Write collision -> 409.
     *
     * <p>Raised by the {@code @Version} column when another transaction changed the
     * row between our read and our write. Distinct from the stale read above: here
     * nothing was out of date when the caller started, they simply lost a genuine
     * race. Safe to retry after re-reading.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail handleConcurrentModification(ObjectOptimisticLockingFailureException e) {
        log.info("Optimistic lock conflict: {}", e.getMessage());
        return problem(HttpStatus.CONFLICT,
                "Concurrent modification",
                "This claim was modified by someone else. Re-read it and retry.",
                "concurrent-modification");
    }

    /**
     * Domain invariant violated -> 400.
     *
     * <p>Reached when the entity rejects something the DTO constraints did not
     * catch. Both layers guard the same rules on purpose: the DTO for a precise
     * message, the domain for a guarantee that holds for every caller.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleInvalidArgument(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", e.getMessage(), "invalid-request");
    }

    /**
     * A path or query parameter that will not convert -> 400.
     *
     * <p>More specific than the base class's {@code TypeMismatchException}
     * handling, so this wins and can name the offending parameter.
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

    // ---------------------------------------------------------------------
    // Overrides of standard Spring MVC handling
    // ---------------------------------------------------------------------

    /**
     * Bean Validation failure -> 400 with per-field messages.
     *
     * <p>Overridden rather than added as a separate {@code @ExceptionHandler}: the
     * base class already maps this type, and declaring a second handler for it in
     * the same advice fails at startup with an ambiguous-mapping error.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(error ->
                fieldErrors.merge(error.getField(), defaultMessage(error.getDefaultMessage()),
                        (first, second) -> first + "; " + second));

        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST,
                "Validation failed", "One or more fields are invalid.", "validation-failed");
        problem.setProperty("errors", fieldErrors);

        return handleExceptionInternal(e, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * Unparseable body -> 400.
     *
     * <p>Covers malformed JSON and unknown enum values. The raw message can echo
     * the payload back, so it is logged rather than returned.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        log.debug("Unreadable request body", e);

        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST,
                "Malformed request",
                "The request body could not be parsed. Check JSON syntax and field types.",
                "malformed-request");

        return handleExceptionInternal(e, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * Unknown route -> 404, in our own shape.
     *
     * <p>The base class produces {@code "No static resource api/foo."}, which is
     * meaningless for a JSON API and quietly describes how request handling works
     * internally. It also arrives without our {@code type} and {@code timestamp},
     * so a client parsing errors would meet an envelope it had not seen before.
     */
    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException e,
                                                                    HttpHeaders headers,
                                                                    HttpStatusCode status,
                                                                    WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.NOT_FOUND,
                "Endpoint not found",
                "No endpoint exists at this path. Check the URL and HTTP method.",
                "endpoint-not-found");

        return handleExceptionInternal(e, problem, headers, HttpStatus.NOT_FOUND, request);
    }

    /**
     * Gives the base class's own problem details a timestamp, so a 405 from a wrong
     * HTTP method has the same envelope as a 409 from our domain. One response
     * format across the whole API, however the error arose.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e,
                                                             Object body,
                                                             HttpHeaders headers,
                                                             HttpStatusCode status,
                                                             WebRequest request) {
        if (body instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey("timestamp"))) {
            problem.setProperty("timestamp", Instant.now());
        }
        return super.handleExceptionInternal(e, body, headers, status, request);
    }

    // ---------------------------------------------------------------------
    // Last resort
    // ---------------------------------------------------------------------

    /**
     * Anything unanticipated -> 500, with nothing revealing in the body.
     *
     * <p>The full trace goes to the log against a generated reference the client
     * also receives, so support can find the exact entry without a stack trace ever
     * crossing the wire. Class names, library versions, and SQL fragments leak that
     * way, and in a regulated environment that is an audit finding.
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
