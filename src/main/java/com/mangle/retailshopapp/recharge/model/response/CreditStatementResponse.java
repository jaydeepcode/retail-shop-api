package com.mangle.retailshopapp.recharge.model.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.mangle.retailshopapp.recharge.domain.Outstanding;
import com.mangle.retailshopapp.recharge.service.CreditLedgerService.StatementLine;

/**
 * One person's tab, every line of it (design-api.md §3.2 endpoint #15).
 *
 * <p>⚠ FROM ANY BUSINESS, newest first. §3.2: "every charge and settlement from
 * any business … with the business each line came from". The business is the one
 * thing this response CANNOT give, and saying so plainly is better than omitting
 * it silently: {@code rc_credit_req} has no business column, and {@code TXN_ID}
 * — the only route to one — is NULL on every row until V13 backfills it on
 * cutover day. The field is therefore absent rather than guessed. design-ledger
 * §6.3 is where it comes from in the end, when the statement reads
 * {@code acc_voucher_line} and each leg names its own {@code AR_*} account.
 *
 * <p>{@code runningBalance} is signed, like the tally it is a running total of,
 * and is the one place in the API where that convention surfaces. The header
 * figures beside it ({@code outstanding}, {@code advanceHeld}) are positive, so
 * a screen can show the summary without interpreting a sign and the ledger
 * column without reinterpreting one.
 */
public record CreditStatementResponse(
        int customerId,
        String name,
        String contactNumber,
        BigDecimal outstanding,
        BigDecimal advanceHeld,
        Integer oldestDebtDays,
        boolean warn,
        PageResponse<StatementLineResponse> lines) {

    public static CreditStatementResponse from(int customerId, String name, String contact,
                                               Outstanding position, LocalDate today,
                                               int warnDays,
                                               PageResponse<StatementLineResponse> lines) {
        return new CreditStatementResponse(
                customerId, name, contact,
                position.owed(), position.advanceHeld(),
                position.ageInDays(today), position.warn(today, warnDays),
                lines);
    }

    /**
     * One line of the tab.
     *
     * <p>{@code extendedBy} is NULL for all 4,301 historical rows — §8.8 found
     * that no record of who extended a legacy credit exists anywhere — so a
     * screen must render its absence as ordinary rather than as missing data.
     */
    public record StatementLineResponse(
            int creditRequestId,
            LocalDateTime at,
            String type,
            BigDecimal amount,
            BigDecimal runningBalance,
            String note,
            String extendedBy,
            Integer paymentId) {

        public static StatementLineResponse from(StatementLine line) {
            return new StatementLineResponse(
                    line.creditRequestId(), line.at(), line.type(), line.amount(),
                    line.runningBalance(), line.note(), line.extendedBy(), line.paymentId());
        }
    }

    /** Convenience for mapping a page of lines. */
    public static List<StatementLineResponse> linesOf(List<StatementLine> lines) {
        return lines.stream().map(StatementLineResponse::from).toList();
    }
}
