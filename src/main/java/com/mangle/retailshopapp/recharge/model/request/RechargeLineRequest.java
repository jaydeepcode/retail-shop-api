package com.mangle.retailshopapp.recharge.model.request;

import java.math.BigDecimal;

import com.mangle.retailshopapp.recharge.domain.ServiceType;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * One recharge in the basket (design-api.md §6.2).
 *
 * <p>Bean validation here is for SHAPE only; the rules that depend on
 * server-resolved data — a live operator, a resolvable customer — are the
 * service's (§6.2: "a rule that depends on server-resolved data … is never a
 * bean-validation annotation").
 *
 * <p>The number pattern is NOT asserted here. §6.2 proposes
 * {@code ^[0-9]{10}$} for mobile and {@code ^[0-9]{6,12}$} for TV, which is
 * cross-field — the rule depends on {@code serviceType} — so it cannot be a field
 * annotation. {@code @Size(max = 20)} holds unconditionally, matching
 * {@code rc_recharge.TARGET_NUMBER}, and the per-type pattern is a class-level
 * concern left for the screen to enforce first: a counter that lets staff type a
 * 9-digit mobile number and only finds out on submit is a worse experience than
 * one that will not accept the keystroke.
 */
public record RechargeLineRequest(

        @NotNull(message = "serviceType is required")
        ServiceType serviceType,

        @NotBlank(message = "a number is required")
        @Size(max = 20, message = "a number is at most 20 characters")
        String number,

        @NotBlank(message = "an operator is required")
        @Size(max = 20, message = "an operator code is at most 20 characters")
        String operatorCode,

        @NotNull(message = "an amount is required")
        @DecimalMin(value = "1.00", message = "a recharge is at least 1.00")
        @Digits(integer = 8, fraction = 2, message = "an amount is held to the paisa")
        BigDecimal amount,

        /**
         * ABSENT means "take the configured default" — ₹10 on TV, zero on mobile.
         * An explicit {@code 0.00} is honoured and must not be re-defaulted, because
         * log §15.11 keeps the field editable: "the staff can change or clear it.
         * The owner was explicit."
         */
        @PositiveOrZero(message = "a surcharge cannot be negative")
        @Digits(integer = 8, fraction = 2, message = "a surcharge is held to the paisa")
        BigDecimal surcharge) {
}
