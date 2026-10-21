package com.mangle.retailshopapp.recharge.model.request;

import java.math.BigDecimal;

import com.mangle.retailshopapp.recharge.domain.ResidualMode;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Refund a failed recharge (design-api.md §3.2 #13).
 *
 * <p>{@code amount} is checked against the recharge's line total rather than
 * trusted: a client that disagrees about the figure is refused rather than
 * quietly overruled, and a partial refund is refused outright because
 * {@code REFUNDED} is terminal and whatever was left behind could never be
 * cleared.
 *
 * <p>{@code mode} carries the third option §3.2 does not name. #13 says "cash
 * back, or reduce the customer's credit tab", which is two; the third is
 * {@code UPI}, which sends a UPI-settled sale back down the rail it arrived on.
 * log §7.4's table named a different third — money the customer asks the shop to
 * keep ({@code KEEP_AS_ADVANCE} → {@code ADV_RECHARGE}) — and that one was
 * WITHDRAWN on 2026-10-21 because nothing ever debits {@code ADV_RECHARGE}, so
 * it changed the liability's shape instead of ending it. See
 * {@link ResidualMode}.
 */
public record RefundRechargeRequest(

        @NotNull(message = "an amount is required")
        @DecimalMin(value = "0.01", message = "a refund is a positive amount")
        @Digits(integer = 8, fraction = 2, message = "an amount is held to the paisa")
        BigDecimal amount,

        @NotNull(message = "a refund mode is required")
        ResidualMode mode,

        /**
         * Derived for {@code CREDIT_REDUCTION}; needed by no mode since
         * {@code KEEP_AS_ADVANCE} was withdrawn. Sent anyway, it still selects
         * whose credit position comes back in the response.
         */
        Integer customerId,

        /**
         * ⚠ REQUIRED WHEN {@code mode = UPI}, where it is the UPI transaction
         * reference and lands on the {@code UPI_CLEARING} leg. Optional otherwise.
         * Not a {@code @NotBlank} here because the rule is conditional on another
         * field — {@code ResidualMode.needsReference()} owns it, and the service
         * raises {@code UPI_REQUIRES_REFERENCE}.
         */
        @Size(max = 255, message = "a note is at most 255 characters")
        String note) {
}
