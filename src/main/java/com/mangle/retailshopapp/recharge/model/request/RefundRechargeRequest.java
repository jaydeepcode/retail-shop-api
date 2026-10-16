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
 * back, or reduce the customer's credit tab", which is two; log §7.4's table has
 * three, the third being money the customer asks the shop to keep
 * ({@code KEEP_AS_ADVANCE} → {@code ADV_RECHARGE}). §7.4 is the later document
 * and it says the three are one posting rule, so all three are accepted here.
 */
public record RefundRechargeRequest(

        @NotNull(message = "an amount is required")
        @DecimalMin(value = "0.01", message = "a refund is a positive amount")
        @Digits(integer = 8, fraction = 2, message = "an amount is held to the paisa")
        BigDecimal amount,

        @NotNull(message = "a refund mode is required")
        ResidualMode mode,

        /** Required by {@code KEEP_AS_ADVANCE}; derived for {@code CREDIT_REDUCTION}. */
        Integer customerId,

        @Size(max = 255, message = "a note is at most 255 characters")
        String note) {
}
