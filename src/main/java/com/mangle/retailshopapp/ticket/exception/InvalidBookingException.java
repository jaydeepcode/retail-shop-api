package com.mangle.retailshopapp.ticket.exception;

import java.math.BigDecimal;
import java.util.Map;

import com.mangle.retailshopapp.recharge.exception.ErrorCode;
import com.mangle.retailshopapp.recharge.exception.RechargeRuleViolationException;

/**
 * 422 — the booking's own figures are impossible.
 *
 * <p>⚠ A 422 AND NOT A BEAN-VALIDATION 400, which is a deliberate reading of
 * design-ticket-booking.md §4: it lists {@code passengerCount < 1} and
 * {@code commissionAmount < 0} among the 422 conditions, each "a rule from this
 * document rather than a generic validation". So they are not annotated on the
 * request record — two layers both refusing the same thing would race, and
 * whichever fired first would decide the status code.
 *
 * <p>⚠ THERE IS NO "MULTIPLE OF 60" RULE, and that absence is the decision (T6).
 * FY2026 holds commissions of ₹3,435 and ₹2,160 on single baskets; a
 * multiple-of-60 check catches the first and waves the second through (36 × 60
 * exactly), so it would give false confidence about the very defect it claims to
 * catch. ₹60 per person is today's price, not a law — the screen defaults it and
 * the staff can change it.
 */
public class InvalidBookingException extends RechargeRuleViolationException {

    private InvalidBookingException(ErrorCode code, String message, Map<String, Object> details) {
        super(code, message, details);
    }

    public static InvalidBookingException passengerCount(Integer given) {
        return new InvalidBookingException(ErrorCode.INVALID_PASSENGER_COUNT,
                "A booking is for at least one passenger; got "
                        + (given == null ? "none" : given) + ".",
                given == null ? Map.of() : Map.of("passengerCount", given));
    }

    public static InvalidBookingException commission(BigDecimal given) {
        return new InvalidBookingException(ErrorCode.INVALID_COMMISSION,
                "Commission cannot be negative; got " + given.toPlainString()
                        + ". Zero is allowed — 17 historical bookings earned nothing.",
                Map.of("commissionAmount", given));
    }
}
