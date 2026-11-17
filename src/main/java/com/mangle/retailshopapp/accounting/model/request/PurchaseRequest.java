package com.mangle.retailshopapp.accounting.model.request;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A float top-up (endpoint 19, design-ledger.md E6/E7, log §2.5).
 *
 * <p>⚠ <b>THIS ENDPOINT WAS MISSING UNTIL 2026-10-07 AND TWO DECISIONS DEPENDED
 * ON IT</b> (log §14.9). FRD §6:138 lists Purchase as one of the six voucher
 * types, "automatic or manual, e.g. topping up Airtel float", and §1.3 shipped
 * manual endpoints for payments, contras and journals while §6 explained why
 * sales and receipts are excluded — <b>Purchase was not mentioned either way</b>.
 * An omission, not a decision. And a load-bearing one: log §8.1:564-566 says the
 * fix for an overdrawn float is "entering the missed {@code PURCHASE}, nothing
 * more", and §8.0:525-528 says the same for a missed top-up restoring
 * {@code FLOAT_X} and {@code BANK} exactly. Both prescribed an action with no
 * endpoint.
 *
 * <p>Three legs: {@code Dr <floatAccountCode>} by the face received,
 * {@code Dr EXP_BANK_CHARGES} by the fee, {@code Cr <sourceAccountCode>} by the
 * two together.
 *
 * <p>⚠ <b>THE FEE IS ITS OWN EXPENSE LEG AND IS NEVER FOLDED INTO THE FLOAT'S
 * COST BASIS</b> (log §2.5:114-118). It is charged for the transfer METHOD, not
 * for the balance, so it is not a cost of goods — and folding it would push the
 * float's cost/face ratio off exactly 1.0, which would end the only independent
 * check the design has on float: the portal balance against the books, rupee for
 * rupee. It is also ₹5 a time and roughly ₹130 a year, which is invisible per
 * top-up and material annually — and "where are we losing money" is the question
 * a visible expense line exists to answer.
 *
 * @param floatAccountCode the wallet. Must have a {@code FLOAT_PATTERN}, which is
 *                         what makes an account a float (V21's header) —
 *                         {@code ALLOW_MANUAL = FALSE} on all four is A7 keeping
 *                         them out of the GENERIC endpoints, and this is the
 *                         endpoint that owns them
 * @param amount           the FACE value received, which equals the cost paid at
 *                         a ratio of 1.0 — all four wallets are FRD §7.2 pattern
 *                         2 (log §2.4:97-99)
 * @param transferFee      <b>optional, and defaults to
 *                         {@code rc_setting['float.imps.fee']}</b>, which
 *                         {@code GET /config} publishes as {@code floatImpsFee}.
 *                         ⚠ Send {@code 0} for a cash top-up: E7 records that no
 *                         IMPS fee arises there, and a zero leg is illegal
 *                         ({@code ck_line_nonzero}), so the fee leg is omitted
 *                         rather than posted at {@code 0.00}
 * @param costPaid         optional, and <b>only to be REFUSED if it disagrees
 *                         with {@code amount}</b>. §1.3's 422 list names "a
 *                         supplied {@code costPaid} differing from {@code amount}
 *                         while that operator is pattern 2", and all four are. It
 *                         exists so a bonus-on-topup wallet is expressible the
 *                         day one is added (FRD §7.2.1) rather than needing a
 *                         contract change — §3.5 calls that machinery dormant,
 *                         not dead
 * @param externalRef      the portal's own top-up reference, stored on
 *                         {@code acc_float_batch.EXTERNAL_REF} so a batch can be
 *                         matched against the vendor's record
 */
public record PurchaseRequest(
        @NotNull(message = "voucherDate is required")
        LocalDate voucherDate,

        @NotBlank(message = "floatAccountCode is required")
        @Size(max = 30, message = "an account code is at most 30 characters")
        String floatAccountCode,

        @NotNull(message = "amount is required")
        BigDecimal amount,

        @NotBlank(message = "sourceAccountCode is required: name the account that paid")
        @Size(max = 30, message = "an account code is at most 30 characters")
        String sourceAccountCode,

        BigDecimal transferFee,

        BigDecimal costPaid,

        @Size(max = 64, message = "an external reference is at most 64 characters")
        String externalRef,

        @Size(max = 255, message = "a narration is at most 255 characters")
        String narration) {
}
