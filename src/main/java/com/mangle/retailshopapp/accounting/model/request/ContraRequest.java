package com.mangle.retailshopapp.accounting.model.request;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Money between the shop's own accounts (endpoint 8, design-ledger.md E20) — the
 * safe to the bank, and the manual sweep.
 *
 * <p>⚠ <b>THIS SHAPE HAD NO SPECIFICATION UNTIL 2026-11-11 AND THE SCREEN IS
 * WHAT SETTLED IT.</b> #8 was named in §1.3's table and in A5 with no payload and
 * no 422 list; the fields below were derived while drawing artboard 13, so they
 * are a decision rather than a transcription and §1.3 now records them as one.
 *
 * <p>Two legs and never a third: {@code Dr toAccountCode / Cr fromAccountCode},
 * and {@code AFFECTS_PL = FALSE} on {@code ref_voucher_type['CONTRA']}
 * (design-ledger.md:122) is what makes it FRD §8.2's "pure asset relocation". A
 * contra that touched P&L would be an expense wearing a transfer's name.
 *
 * <p>⚠ <b>THE ENDPOINT ACCEPTS THE TILLS AND THE SCREEN DOES NOT OFFER THEM</b>,
 * and that difference is deliberate (§1.3). Eight accounts pass the rule —
 * {@code CASH_SAFE}, {@code CASH_WATER}, {@code CASH_RECHARGE},
 * {@code CASH_TICKET} and the four banks — and the screen offers six, leaving the
 * tills out because they empty themselves every night from #2, so a hand-typed
 * leg against one is almost always someone doing the sweep by hand. That is a
 * screen decision, not a rule: #8's own purpose line says "and the manual sweep",
 * so nothing is blocked.
 *
 * @param fromAccountCode where the money leaves. An {@code ASSET} in
 *                        {@code REPORT_GROUP = 'CASH_AND_BANK'} with
 *                        {@code ALLOW_MANUAL = TRUE} — which is what keeps
 *                        {@code UPI_CLEARING} and the four floats out of the
 *                        picker (A7). Each is moved by the endpoint that owns it,
 *                        #6 and #19, and each has its own screen
 * @param toAccountCode   where it arrives. Same rule, and not equal to
 *                        {@code fromAccountCode}: a {@code Dr X / Cr X} contra
 *                        balances, passes every check and moves nothing
 */
public record ContraRequest(
        @NotNull(message = "voucherDate is required")
        LocalDate voucherDate,

        @NotBlank(message = "fromAccountCode is required")
        @Size(max = 30, message = "an account code is at most 30 characters")
        String fromAccountCode,

        @NotBlank(message = "toAccountCode is required")
        @Size(max = 30, message = "an account code is at most 30 characters")
        String toAccountCode,

        @NotNull(message = "amount is required")
        BigDecimal amount,

        @Size(max = 255, message = "a narration is at most 255 characters")
        String narration) {
}
