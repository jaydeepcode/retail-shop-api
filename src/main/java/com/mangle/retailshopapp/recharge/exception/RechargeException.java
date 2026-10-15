package com.mangle.retailshopapp.recharge.exception;

import java.util.Map;

/**
 * The root of the module's error tree (design-api.md §8.2).
 *
 * <p>⚠ THE STATUS LIVES ON THE EXCEPTION, not in the handler. That is the whole
 * design: one {@code @ExceptionHandler} covers the entire tree, instead of
 * {@code RegistrationExceptionHandler}'s seven near-identical methods
 * ({@code customer/exception/RegistrationExceptionHandler.java:14-88}), each
 * repeating the same body with a different exception type. Adding a new failure
 * becomes a new subclass and no handler change.
 *
 * <p>Deliberately unchecked and deliberately not Spring's
 * {@code ResponseStatusException}: {@code details} carries machine-usable numbers
 * that §8.4 wants on the wire ({@code LINES_DO_NOT_SUM} carries both totals so
 * the UI can show the shortfall), and {@code ResponseStatusException} has nowhere
 * to put them.
 */
public abstract class RechargeException extends RuntimeException {

    private final ErrorCode code;
    private final int status;
    private final Map<String, Object> details;

    protected RechargeException(ErrorCode code, int status, String message) {
        this(code, status, message, Map.of());
    }

    protected RechargeException(ErrorCode code, int status, String message,
                                Map<String, Object> details) {
        super(message);
        this.code = code;
        this.status = status;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public ErrorCode code() {
        return code;
    }

    public int status() {
        return status;
    }

    /** Empty rather than null, so the handler never branches on it. */
    public Map<String, Object> details() {
        return details;
    }
}
