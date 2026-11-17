package com.mangle.retailshopapp.accounting.model.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What is still in transit, grouped by collection date and aged (endpoint 5,
 * §1.2).
 *
 * <p>⚠ <b>AGEING IS THE POINT OF THIS ENDPOINT, NOT THE TOTAL.</b>
 * {@code UPI_CLEARING}'s balance already answers "how much is in transit" and
 * {@code /reports/balance-sheet} will show it; what nothing else can say is that
 * ₹250 of it has been in transit since September. A settlement has never once
 * mismatched in seven years (log §9.2), so the first time one does, the only
 * thing that will make it visible is a row here that has stopped getting younger.
 *
 * @param asOf         the ageing date, defaulting to today. Collections dated
 *                     after it are excluded rather than aged negatively
 * @param totalPending the sum of the rows. ⚠ Equal to {@code UPI_CLEARING}'s
 *                     derived balance whenever every settlement went through
 *                     endpoint 6 — which is an invariant worth testing, because
 *                     it is what says no settlement was recorded by some other
 *                     route
 * @param dates        oldest first, which is the order the money should arrive in
 */
public record UpiPendingResponse(
        LocalDate asOf,
        BigDecimal totalPending,
        List<PendingDate> dates) {

    /**
     * One collection date.
     *
     * @param collectedAmount what customers paid by UPI that day
     * @param settledAmount   what the bank has credited against it so far, across
     *                        however many settlements A4 allowed
     * @param pendingAmount   the difference, and what endpoint 6 will accept at
     *                        most. ⚠ <b>Can be negative</b>, and a negative row is
     *                        shown rather than hidden: a correcting JOURNAL that
     *                        credits {@code UPI_CLEARING} is dated the day the
     *                        mistake was FOUND (FRD §8.3), so it reduces that day
     *                        and can take it below what was settled against it. A
     *                        filtered-out negative is a reconciliation that
     *                        silently stops adding up
     * @param ageDays         days from {@code collectionDate} to {@code asOf}. 0
     *                        on the day itself
     */
    public record PendingDate(
            LocalDate collectionDate,
            BigDecimal collectedAmount,
            BigDecimal settledAmount,
            BigDecimal pendingAmount,
            long ageDays) {
    }
}
