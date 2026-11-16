package com.mangle.retailshopapp.accounting.model.request;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * What the bank actually credited (endpoint 6, design-ledger.md E16/E16b).
 *
 * <p>⚠ <b>A4: THIS ACCEPTS THE AMOUNT THAT ARRIVED, AND MAY BE CALLED MORE THAN
 * ONCE FOR ONE COLLECTION DATE</b> (log §9.2). ₹3,450 collected and ₹3,200
 * landing is a partial settlement: the ₹250 stays in {@code UPI_CLEARING} and
 * ages on endpoint 5's screen until it arrives, and <b>nothing is written off
 * automatically</b> — no write-off account exists, because a settlement has never
 * once mismatched in seven years.
 *
 * <p>⚠ <b>THE BANK IS NOT ON THIS PAYLOAD AND MUST NOT BE</b> (log §9.3). §1.2
 * resolves it server-side from {@code rc_setting['bank.default.account']},
 * "never a constant" and never a client's choice — which bank the shop's money
 * lands in is configuration, and a client that could send it could send the wrong
 * one. It is also why {@code GET /config} withholds that setting.
 *
 * @param collectionDate the day the money was taken FROM CUSTOMERS. This is what
 *                       identifies the settlement — the sequence
 *                       {@code UPI_SETTLEMENT:<collectionDate>:<n>} is built on
 *                       it, and endpoint 5 ages by it
 * @param settledOn      the day it landed in the bank, and the voucher's
 *                       accounting date. Never before {@code collectionDate}:
 *                       money cannot arrive before it was taken
 * @param settledAmount  what the bank credited, <b>not necessarily the collected
 *                       total</b>. Positive, and never more than what is still
 *                       pending for that date
 * @param note           free text, e.g. "₹250 short, not yet arrived". The only
 *                       record of why a date is part-settled
 */
public record UpiSettlementRequest(
        @NotNull(message = "collectionDate is required: it is what the settlement is against")
        LocalDate collectionDate,

        @NotNull(message = "settledOn is required: the day the money landed")
        LocalDate settledOn,

        @NotNull(message = "settledAmount is required")
        BigDecimal settledAmount,

        @Size(max = 255, message = "a note is at most 255 characters")
        String note) {
}
