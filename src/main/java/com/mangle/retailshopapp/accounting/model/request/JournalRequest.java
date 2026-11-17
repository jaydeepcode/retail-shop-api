package com.mangle.retailshopapp.accounting.model.request;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A correction (endpoint 9, FRD §8.3, design-ledger.md E21).
 *
 * <p>⚠ <b>THE ONLY ENDPOINT THAT ACCEPTS FREE LEGS, AND THE ONLY ONE THAT NEEDS
 * TO</b> (§1.3): a correction can touch any pair of accounts, so a fixed shape
 * cannot express it. Everything else goes through an endpoint per voucher type
 * (A5), because a generic {@code POST /vouchers} would have to validate six leg
 * shapes from one payload and would let a caller hand-write a {@code SALES}
 * voucher, bypassing the float legs, the commission computation and the
 * idempotency keys that make those correct.
 *
 * <p>⚠ <b>A JOURNAL IS ACCEPTED AT ANY TIME, AND THE 422 THAT SAID OTHERWISE WAS
 * WITHDRAWN ON 2026-11-02</b> (§1.3). It read: "422 on a journal whose target
 * account is not yet reconciled, with the message pointing at delete-and-repost
 * instead." The principle was right and <b>the enforcement point was wrong</b>.
 * The caller that knows whether an amendment is possible is the module that owns
 * the record — the ticket module amends and re-posts while the day is open and
 * posts a dated journal once it is closed, choosing for itself
 * ({@code ticket-corrections.md}) — while a ledger-native voucher, an expense or
 * a contra, <b>has no operational record to amend at all</b>. For those the
 * refusal removed the only correction route they had: a wrong expense posted at
 * 4pm could not be fixed by any means until the drawer was counted that night.
 *
 * <p>⚠ <b>{@code ALLOW_MANUAL = FALSE} IS STILL REFUSED HERE</b> (A7), and that
 * is not the same rule. The float accounts, {@code UPI_CLEARING} and
 * {@code RCHG_PENDING} are machine-owned: a hand-posted leg would corrupt a
 * reconciliation something else is responsible for. They move through the
 * endpoints that own them — #19 for a top-up, #4 for a float correction, #6 for a
 * settlement.
 *
 * @param voucherDate       <b>the day the mistake was FOUND</b>, not the day of
 *                          the entry being corrected (FRD §8.3). The original
 *                          stays byte-identical and a back-dated correction would
 *                          restate a period that has already been reported
 * @param reversesVoucherId the voucher being corrected, when there is one. Set on
 *                          {@code acc_voucher.REVERSES_VOUCHER_ID}, which is how
 *                          a reader gets from the correction to the original.
 *                          Optional: a correction may have no single culprit
 * @param legs              at least two, debits equal to credits
 */
public record JournalRequest(
        @NotNull(message = "voucherDate is required")
        LocalDate voucherDate,

        Long reversesVoucherId,

        @NotEmpty(message = "a journal needs legs")
        @Valid
        List<JournalLeg> legs,

        @NotBlank(message = "narration is required: a correction has to say what it corrects")
        @Size(max = 255, message = "a narration is at most 255 characters")
        String narration) {

    /**
     * One leg, exactly one side of it filled.
     *
     * <p>⚠ TWO NULLABLE FIELDS RATHER THAN AN AMOUNT AND A SIDE ENUM, because
     * that is the shape §1.3's own example publishes:
     * {@code {"accountCode": "INC_RCHG_COMM", "dr": 50.00}}. The service converts
     * to {@code VoucherLeg}, where exactly one side carries a positive amount and
     * the other is zero — the shape {@code ck_line_oneside} and
     * {@code ck_line_nonzero} enforce in the database.
     *
     * @param partyCustId required on an account with {@code REQUIRES_PARTY}: the
     *                    {@code AR_*} and {@code ADV_*} accounts carry a customer
     *                    dimension, and a receivable with no party cannot appear
     *                    on anyone's statement (FRD §8.7)
     */
    public record JournalLeg(
            @NotBlank(message = "every leg needs an accountCode")
            @Size(max = 30, message = "an account code is at most 30 characters")
            String accountCode,

            BigDecimal dr,

            BigDecimal cr,

            Integer partyCustId,

            @Size(max = 255, message = "a line narration is at most 255 characters")
            String lineNarration) {
    }
}
