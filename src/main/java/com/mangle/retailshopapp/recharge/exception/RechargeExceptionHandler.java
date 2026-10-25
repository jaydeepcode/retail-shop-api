package com.mangle.retailshopapp.recharge.exception;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.mangle.retailshopapp.recharge.controller.RechargeController;
import com.mangle.retailshopapp.ticket.controller.TicketController;
import com.mangle.retailshopapp.recharge.model.response.ErrorResponse;
import com.mangle.retailshopapp.recharge.model.response.ErrorResponse.FieldError;

import jakarta.validation.ConstraintViolationException;

/**
 * The module's error model (design-api.md §8.3).
 *
 * <p>⚠ ONE HANDLER FOR THE WHOLE {@code RechargeException} TREE, because the
 * status lives on the exception. That is the opposite of
 * {@code RegistrationExceptionHandler:14-88}, which repeats seven near-identical
 * methods differing only in the exception type — and which returns
 * {@code ex.getMessage()} to the client, leaking whatever the exception happened
 * to say.
 *
 * <p>Scoped to named controllers with {@code assignableTypes} rather than applied
 * globally. A global advice here would change the error shape of every existing
 * endpoint in the application, which is not S3's to do: the other modules have
 * their own conventions, including the {@code Map.of("success", …)} bodies §8.4
 * criticises, and changing them is a separate piece of work with its own
 * client-side consequences.
 *
 * <p>⚠ EVERY CONTROLLER SHARING THIS ERROR MODEL MUST BE NAMED ABOVE, and
 * forgetting one is silent. {@code TicketController} was added for S4: its 422s
 * are the same {@code ErrorCode} vocabulary and the same
 * {@code RechargeException} tree, but until it appeared in that list NOTHING
 * handled them. A ticket booking refused by {@code ck_tkt_funding} would have
 * fallen through to Spring's default error body — a bare 500 with no
 * {@code code} — which passes every service unit test and fails the first time
 * anyone uses the screen. {@code TicketControllerErrorsTest} is the guard: it
 * asserts a ticket 422 arrives as a 422 carrying its code.
 *
 * <p>The class name is now wider than its package. {@code recharge.exception} is
 * where the shared vocabulary lives — next-session-s4.md requires reusing
 * {@code ErrorCode} and this hierarchy "rather than starting a second one",
 * because §8.4's "these names are a contract" holds across the whole surface —
 * and it equally says to "rename the package if {@code recharge.exception} reads
 * wrong for a shared thing, but say so in the diff". It does read wrong. 41
 * files import it, so the rename is its own commit rather than buried in a
 * feature.
 *
 * <p><b>The 500 handler is the one that matters most.</b> It returns a fixed
 * message and a generated {@code traceId}, and logs the stack trace at ERROR
 * against that id. The client learns nothing about the internals and support can
 * still find the exact failure.
 */
@RestControllerAdvice(assignableTypes = {RechargeController.class, TicketController.class})
public class RechargeExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RechargeExceptionHandler.class);

    /**
     * The whole tree, 400 through 422, in one method.
     *
     * <p>Logged at WARN rather than ERROR: these are expected outcomes of a
     * counter being used by a human — a basket that does not sum, a recharge
     * someone else already completed — and logging them at ERROR would bury the
     * failures that actually need attention.
     */
    @ExceptionHandler(RechargeException.class)
    public ResponseEntity<ErrorResponse> handleRecharge(RechargeException ex) {
        String traceId = newTraceId();
        log.warn("[{}] {} {}: {}", traceId, ex.status(), ex.code(), ex.getMessage());
        return ResponseEntity.status(ex.status())
                .body(new ErrorResponse(ex.code(), ex.getMessage(), traceId, null, ex.details()));
    }

    /**
     * Bean validation, flattened so the counter can put the cursor on the field.
     *
     * <p>The index path is what makes this useful on a basket:
     * {@code recharges[1].amount} tells the client WHICH line, which a flat field
     * name could not (§8.3).
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleInvalidBody(MethodArgumentNotValidException ex) {
        List<FieldError> fields = new ArrayList<>();
        ex.getBindingResult().getFieldErrors().forEach(e ->
                fields.add(new FieldError(e.getField(), e.getDefaultMessage())));
        ex.getBindingResult().getGlobalErrors().forEach(e ->
                fields.add(new FieldError(e.getObjectName(), e.getDefaultMessage())));

        String traceId = newTraceId();
        log.warn("[{}] 400 INVALID_REQUEST: {}", traceId, fields);
        return ResponseEntity.badRequest().body(new ErrorResponse(
                ErrorCode.INVALID_REQUEST, "The request is not valid.", traceId, fields, null));
    }

    /** Query-parameter constraints, from {@code @Validated} on the controller. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleInvalidParams(ConstraintViolationException ex) {
        List<FieldError> fields = ex.getConstraintViolations().stream()
                .map(v -> new FieldError(v.getPropertyPath().toString(), v.getMessage()))
                .toList();

        String traceId = newTraceId();
        log.warn("[{}] 400 INVALID_REQUEST: {}", traceId, fields);
        return ResponseEntity.badRequest().body(new ErrorResponse(
                ErrorCode.INVALID_REQUEST, "The request is not valid.", traceId, fields, null));
    }

    /**
     * Unparseable JSON, or an unknown enum value.
     *
     * <p>⚠ THE MESSAGE IS REPLACED, NEVER ECHOED (§8.3). Jackson's own text names
     * Java classes and package paths, and for an unknown enum value it enumerates
     * the accepted constants — which is a small internals leak and, worse, reads
     * as a stack trace to the person at the counter.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        String traceId = newTraceId();
        log.warn("[{}] 400 unreadable request body", traceId, ex);
        return ResponseEntity.badRequest().body(new ErrorResponse(
                ErrorCode.INVALID_REQUEST, "The request body could not be read.",
                traceId, null, null));
    }

    /**
     * A query or path value that could not be converted to its parameter type.
     *
     * <p>⚠ WITHOUT THIS, EVERY ONE OF THESE WAS A 500. Spring raises
     * {@code MethodArgumentTypeMismatchException} during argument resolution, and
     * the only handler that matched it was {@code Exception} — so
     * {@code ?status=BOGUS}, {@code ?page=abc}, {@code ?date=2026-13-45} and
     * {@code /number-hints/MOBILEX/9999999999} all returned
     * {@code INTERNAL_ERROR / "An unexpected error occurred."} and logged a stack
     * trace at ERROR, for what are plain client mistakes. The module already
     * handled the equivalent failure in the request BODY
     * ({@code HttpMessageNotReadableException} → 400) and not in the path or the
     * query string.
     *
     * <p><b>The accepted values are named for an enum, and that is not a leak.</b>
     * {@code ServiceType}, {@code SettlementMethod} and {@code RechargeStatus}
     * are part of the published contract — §6.1: "enums cross the wire as their
     * string names" — so telling a client which ones exist tells it nothing it
     * should not already know, and saves a round trip through the documentation.
     *
     * <p>What is NOT echoed is Spring's own message, which names Java classes and
     * package paths. Same discipline as the body case: the message is replaced,
     * never forwarded.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex) {
        String traceId = newTraceId();
        String message = describe(ex);
        log.warn("[{}] 400 INVALID_REQUEST: {}", traceId, message);
        return ResponseEntity.badRequest().body(new ErrorResponse(
                ErrorCode.INVALID_REQUEST, "The request is not valid.", traceId,
                List.of(new FieldError(ex.getName(), message)), null));
    }

    /**
     * A sentence naming the parameter and what it will accept.
     *
     * <p>Deliberately specific per kind, because a counter that has just typed a
     * filter wants to know what to type instead rather than that something was
     * "invalid".
     */
    private static String describe(MethodArgumentTypeMismatchException ex) {
        Class<?> required = ex.getRequiredType();
        if (required == null) {
            return "is not a value this parameter accepts";
        }
        if (required.isEnum()) {
            return "must be one of: " + Arrays.stream(required.getEnumConstants())
                    .map(c -> ((Enum<?>) c).name())
                    .collect(Collectors.joining(", "));
        }
        if (LocalDate.class.equals(required)) {
            return "must be a date in the form 2026-10-14";
        }
        if (Number.class.isAssignableFrom(required)
                || int.class.equals(required) || long.class.equals(required)) {
            return "must be a whole number";
        }
        return "is not a value this parameter accepts";
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
                       MissingRequestHeaderException.class})
    public ResponseEntity<ErrorResponse> handleMissing(Exception ex) {
        String traceId = newTraceId();
        log.warn("[{}] 400 {}", traceId, ex.getMessage());
        return ResponseEntity.badRequest().body(new ErrorResponse(
                ErrorCode.INVALID_REQUEST, ex.getMessage(), traceId, null, null));
    }

    /**
     * Someone else wrote first.
     *
     * <p>Kept even though the module locks pessimistically: Spring can still
     * raise this from a flush ordering a lock did not cover, and a 409 that tells
     * the client to refresh is the right answer either way.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleConcurrent(OptimisticLockingFailureException ex) {
        String traceId = newTraceId();
        log.warn("[{}] 409 CONCURRENT_MODIFICATION", traceId, ex);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(
                ErrorCode.CONCURRENT_MODIFICATION,
                "Someone else changed this first. Refresh and try again.",
                traceId, null, null));
    }

    /**
     * A constraint the service did not anticipate.
     *
     * <p>⚠ NEVER THE SQL OR THE CONSTRAINT NAME (§8.3).
     * {@code RegistrationExceptionHandler:64} returns exactly that, which tells
     * the client the schema. The real text is logged against the trace id.
     *
     * <p>A duplicate {@code Idempotency-Key} reaches here, and the CONTROLLER
     * intercepts that case before this handler sees it — §7.1's insert-then-
     * recover flow needs a fresh read-only transaction to find the original
     * result, because the aborted one cannot see its own rolled-back row.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleIntegrity(DataIntegrityViolationException ex) {
        String traceId = newTraceId();
        log.error("[{}] 409 DATA_INTEGRITY_VIOLATION", traceId, ex);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(
                ErrorCode.DATA_INTEGRITY_VIOLATION,
                "That conflicts with something already recorded.", traceId, null, null));
    }

    /**
     * Everything else.
     *
     * <p>A fixed message plus a trace id, with the stack trace logged at ERROR
     * against that id. §8.3: "The contrast with
     * {@code RegistrationExceptionHandler:59-66} is the whole point."
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        String traceId = newTraceId();
        log.error("[{}] 500 unexpected failure", traceId, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse(
                ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.",
                traceId, null, null));
    }

    /** Short enough to read aloud over a phone, which is how it will be used. */
    private static String newTraceId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
