package com.mangle.retailshopapp.accounting.model.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.mangle.retailshopapp.accounting.model.AccReconciliation;

/**
 * One {@code acc_reconciliation} row on the wire — endpoint 3's page element,
 * endpoint 4's body, and one member inside a {@link PoolCloseResponse}.
 *
 * <p>{@code varianceAmount} is read back from the row rather than recomputed
 * here: it is a generated STORED column, so the database is the only thing that
 * derives it and a second subtraction in Java would be a second chance to
 * disagree.
 */
public record ReconciliationResponse(
        long reconId,
        String accountCode,
        LocalDate reconDate,
        BigDecimal countedAmount,
        BigDecimal ledgerAmount,
        BigDecimal varianceAmount,
        BigDecimal faceCounted,
        BigDecimal faceLedger,
        String note,
        Long varianceVoucherId,
        LocalDateTime closedAt) {

    public static ReconciliationResponse from(AccReconciliation r) {
        return new ReconciliationResponse(
                r.getReconId(),
                r.getAccountCode(),
                r.getReconDate(),
                r.getCountedAmount(),
                r.getLedgerAmount(),
                r.getVarianceAmount(),
                r.getFaceCounted(),
                r.getFaceLedger(),
                r.getNote(),
                r.getVarianceVoucherId(),
                r.getClosedAt());
    }
}
