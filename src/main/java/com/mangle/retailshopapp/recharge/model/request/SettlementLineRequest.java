package com.mangle.retailshopapp.recharge.model.request;

import java.math.BigDecimal;

import com.mangle.retailshopapp.settlement.SettlementMethod;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One tender (design-api.md §6.2).
 *
 * <p>{@code customerId} is nullable at the bean level and REQUIRED for
 * {@code CREDIT} at the service level — a cross-field rule, and one that
 * {@code SettlementPlan} and {@code CreditLedgerService} both enforce (§5.3's two
 * layers).
 *
 * <p>{@code amountTendered} is meaningful only on {@code CASH}. Sending it on a
 * {@code UPI} line is refused rather than ignored, because a client that thinks
 * it is handing over cash on a UPI line has a bug worth surfacing.
 */
public record SettlementLineRequest(

        @NotNull(message = "a payment method is required")
        SettlementMethod method,

        @NotNull(message = "an amount is required")
        @DecimalMin(value = "0.01", message = "a payment line is at least 0.01")
        @Digits(integer = 8, fraction = 2, message = "an amount is held to the paisa")
        BigDecimal amount,

        @DecimalMin(value = "0.00", message = "a tender cannot be negative")
        @Digits(integer = 8, fraction = 2, message = "a tender is held to the paisa")
        BigDecimal amountTendered,

        @Size(max = 64, message = "a reference is at most 64 characters")
        String reference,

        Integer customerId,

        @Size(max = 255, message = "a note is at most 255 characters")
        String note) {
}
