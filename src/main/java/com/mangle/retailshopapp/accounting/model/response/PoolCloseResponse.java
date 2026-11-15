package com.mangle.retailshopapp.accounting.model.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What one day close wrote (endpoint 2).
 *
 * <p>⚠ {@code sweep} IS A STATUS AND NOT A NULLABLE VOUCHER ID, which §1.1's
 * zero-leg correction asks for in as many words: "the response says the sweep was
 * skipped rather than returning a null voucher id, which reads as a failure". A
 * day that genuinely took nothing still gets its {@code acc_reconciliation} rows
 * — they are the evidence the count happened — and gets no sweep voucher.
 *
 * <p>This is also the body of the <b>409</b> a replay returns. §1.1's code list
 * says 409 "already closed for that date" and §1.3 says the service re-reads and
 * returns "the original close rather than a second sweep"; both hold only if the
 * 409 carries this record. The precedent is {@code RechargeController.java:593},
 * where a duplicate mobile answers 409 with the EXISTING customer rather than a
 * bare error, for the same reason: a bare 409 leaves the operator stuck with a
 * drawer in front of them and no figures.
 *
 * @param replayed true when this close already existed and nothing was written
 */
public record PoolCloseResponse(
        String poolCode,
        LocalDate reconDate,
        BigDecimal countedAmount,
        BigDecimal expectedAmount,
        BigDecimal varianceAmount,
        Long varianceVoucherId,
        Sweep sweep,
        List<ReconciliationResponse> members,
        boolean replayed) {

    /**
     * @param voucherId the {@code CONTRA}, or null when nothing was swept
     * @param amount    what reached {@code CASH_SAFE}; {@code 0.00} when skipped
     * @param status    {@code POSTED}, or {@code SKIPPED_ALL_MEMBERS_ZERO}
     */
    public record Sweep(Long voucherId, BigDecimal amount, SweepStatus status) {

        public enum SweepStatus {
            POSTED,
            /**
             * Every member's recorded balance was zero, so there was nothing to
             * move and {@code ck_line_nonzero CHECK (DR_AMOUNT + CR_AMOUNT > 0)}
             * would have refused the voucher anyway (§1.1, "A zero leg is
             * illegal").
             */
            SKIPPED_ALL_MEMBERS_ZERO
        }
    }
}
