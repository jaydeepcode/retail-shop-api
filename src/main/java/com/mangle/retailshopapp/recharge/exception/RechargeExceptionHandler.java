package com.mangle.retailshopapp.recharge.exception;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

import com.mangle.retailshopapp.recharge.controller.RechargeController;
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
 * <p>Scoped to this controller with {@code assignableTypes} rather than applied
 * globally. A global advice here would change the error shape of every existing
 * endpoint in the application, which is not S3's to do: the other modules have
 * their own conventions, including the {@code Map.of("success", …)} bodies §8.4
 * criticises, and changing them is a separate piece of work with its own
 * client-side consequences.
 *
 * <p><b>The 500 handler is the one that matters most.</b> It returns a fixed
 * message and a generated {@code traceId}, and logs the stack trace at ERROR
 * against that id. The client learns nothing about the internals and support can
 * still find the exact failure.
 */
@RestControllerAdvice(assignableTypes = RechargeController.class)
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
