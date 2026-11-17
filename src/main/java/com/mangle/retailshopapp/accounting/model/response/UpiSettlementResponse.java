package com.mangle.retailshopapp.accounting.model.response;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One recorded settlement (endpoint 6).
 *
 * <p>Carries the figures the screen needs to show what is left in transit without
 * a second call, and the sequence that identifies this settlement among the
 * others for its collection date.
 *
 * @param sequence         {@code UPI_SETTLEMENT:<collectionDate>:<n>}, stored on
 *                         {@code acc_voucher.SOURCE_REF}. ⚠ <b>This is the
 *                         published identity of the settlement and it is NOT the
 *                         idempotency key</b> — see {@code UpiSettlementService}
 *                         for why the two parted company (owner, 2026-11-16)
 * @param bankAccountCode  where the money landed, resolved server-side from
 *                         {@code rc_setting['bank.default.account']} (log §9.3).
 *                         Returned so the client can show it, never sent by one
 * @param settledToDate    everything credited against this collection date,
 *                         including this call
 * @param residualAmount   what is still in transit for this date after this call.
 *                         ⚠ <b>Positive is normal and is not an error</b>: it ages
 *                         on endpoint 5 and nothing writes it off (§1.2)
 * @param reconciliation   the {@code acc_reconciliation} row this settlement
 *                         wrote or extended — the evidence for the voucher's
 *                         amount (§2). Its {@code varianceAmount} is the negated
 *                         residual, because {@code COUNTED - LEDGER} is
 *                         settled-minus-collected for a UPI row
 * @param replayed         true when this exact {@code Idempotency-Key} had
 *                         already been recorded and nothing was written; the body
 *                         of the 409
 */
public record UpiSettlementResponse(
        LocalDate collectionDate,
        LocalDate settledOn,
        BigDecimal settledAmount,
        BigDecimal collectedAmount,
        BigDecimal settledToDate,
        BigDecimal residualAmount,
        String sequence,
        String bankAccountCode,
        long voucherId,
        ReconciliationResponse reconciliation,
        boolean replayed) {
}
