package com.mangle.retailshopapp.recharge.model.response;

import java.util.List;
import java.util.Map;

import com.mangle.retailshopapp.recharge.exception.ErrorCode;

/**
 * One shape for every error (design-api.md §8.4).
 *
 * <p>⚠ NO {@code success} FIELD. The status code is the outcome. §8.4 is blunt
 * about where the alternative leads: every {@code Map.of("success", …)} body in
 * {@code AuthenticationController} and {@code CustomerApprovalController} is a
 * body the client must parse to learn something HTTP already told it, and
 * {@code PurchasePartyController:110-113} shows the end of that road — a
 * duplicate registration returned as HTTP 500.
 *
 * <p>{@code code} is the field that earns its place: a keyboard-driven counter
 * has to decide WHERE TO PUT THE CURSOR when a settle is refused, and it cannot
 * do that from prose. {@code message} may be reworded freely; {@code code} may
 * not.
 *
 * <p>{@code traceId} is on every error and logged server-side. It is what
 * REPLACES leaking {@code ex.getMessage()} — the contrast with
 * {@code RegistrationExceptionHandler:59-66}, which returns the raw exception
 * text to the client, is the whole point.
 */
public record ErrorResponse(
        ErrorCode code,
        String message,
        String traceId,
        List<FieldError> fieldErrors,
        Map<String, Object> details) {

    /** Carries the index path, e.g. {@code recharges[1].amount} (§8.3). */
    public record FieldError(String field, String message) {
    }
}
