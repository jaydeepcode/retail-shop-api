package com.mangle.retailshopapp.recharge.model.request;

import java.math.BigDecimal;

import com.mangle.retailshopapp.recharge.domain.ResidualMode;
import com.mangle.retailshopapp.recharge.domain.SettlementMethod;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Retry a failed recharge (design-api.md §3.3, §6.2).
 *
 * <p>Shape only, as §6.2 requires — whether the difference actually needs
 * settling depends on the ORIGINAL amount, which the server holds and the client
 * does not send. That makes it a service rule and never a bean-validation
 * annotation.
 *
 * <p><b>⚠ {@code topUp} IS NOT IN §3.3's CONTRACT.</b> §3.3 shows only
 * {@code amount} and {@code differenceSettlement}, and #12's 422 list names only
 * the owed-back direction — the written contract has no way to say money is
 * coming IN. Log §7.4 nonetheless states that "the same mechanism covers the
 * opposite direction", replacing design-ledger E11's delta voucher, so the
 * higher-amount retry is implemented and this field carries it. Recorded as a
 * deliberate extension rather than left to be discovered.
 */
public record RetryRechargeRequest(

        /** The new FACE VALUE. The original line's surcharge carries over. */
        @NotNull(message = "an amount is required")
        @DecimalMin(value = "1.00", message = "a retry is at least 1.00")
        @Digits(integer = 8, fraction = 2, message = "an amount is held to the paisa")
        BigDecimal amount,

        /** Required when the retry is for LESS, refused when it is not. */
        @Valid DifferenceSettlement differenceSettlement,

        /** Required when the retry is for MORE, refused when it is not. */
        @Valid TopUp topUp) {

    /**
     * How the remainder gets back to the customer (log §7.4's table).
     *
     * <p>{@code customerId} is needed by {@code KEEP_AS_ADVANCE} and ignored by
     * {@code CREDIT_REDUCTION} — the latter's customer is a fact about the
     * original payment's credit line, not a choice, because
     * design-api.md:789 scopes a credit reduction to "that line's customer".
     */
    public record DifferenceSettlement(
            @NotNull(message = "a settlement mode is required") ResidualMode mode,
            Integer customerId,
            @Size(max = 255, message = "a note is at most 255 characters") String note) {
    }

    /** How the extra is taken on a retry that costs more (log §7.4). */
    public record TopUp(
            @NotNull(message = "a top-up method is required") SettlementMethod method,
            Integer customerId,
            @Size(max = 255, message = "a note is at most 255 characters") String note) {
    }
}
