package com.mangle.retailshopapp.recharge.exception;

/**
 * The stable machine-readable error codes (design-api.md §8.4).
 *
 * <p>⚠ THESE NAMES ARE A CONTRACT. §8.4: "{@code message} is for humans and may
 * be reworded freely; {@code code} may not." The reason is concrete rather than
 * stylistic — a keyboard-driven counter has to decide WHERE TO PUT THE CURSOR
 * when a settle is refused, and it cannot do that from prose. Renaming a constant
 * here breaks a screen silently.
 */
public enum ErrorCode {

    // 400
    INVALID_REQUEST,
    INVALID_FINANCIAL_YEAR,

    // 404
    RECHARGE_NOT_FOUND,
    PAYMENT_NOT_FOUND,
    CUSTOMER_NOT_FOUND,
    UNKNOWN_OPERATOR,
    UNKNOWN_ROUTE,
    BOOKING_NOT_FOUND,

    // 409
    ILLEGAL_STATUS_TRANSITION,
    FAILURE_ALREADY_RESOLVED,
    DUPLICATE_RETRY,
    CONCURRENT_MODIFICATION,
    DUPLICATE_CUSTOMER_MOBILE,
    DATA_INTEGRITY_VIOLATION,

    // 422
    LINES_DO_NOT_SUM,
    CREDIT_REQUIRES_CUSTOMER,
    UPI_REQUIRES_REFERENCE,
    INVALID_TENDER,
    UNSETTLED_DIFFERENCE,
    REFUND_MODE_NOT_AVAILABLE,
    TOO_MANY_CREDIT_LINES,
    NO_COMMISSION_RATE,
    FLOAT_ACCOUNT_NOT_DERIVABLE,

    // 422 - ticket booking (design-ticket-booking.md §4). Added to THIS enum
    // rather than a second one: §8.4's "these names are a contract" holds across
    // the whole surface, and a counter deciding where to put the cursor cannot do
    // it from two vocabularies.
    TICKET_FUNDING_MISMATCH,
    UNKNOWN_FUNDING_ACCOUNT,
    INVALID_PASSENGER_COUNT,
    INVALID_COMMISSION,

    // 500
    INTERNAL_ERROR
}
