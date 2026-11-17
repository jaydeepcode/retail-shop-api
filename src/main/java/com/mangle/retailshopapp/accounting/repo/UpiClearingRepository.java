package com.mangle.retailshopapp.accounting.repo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.mangle.retailshopapp.accounting.model.AccVoucher;

/**
 * What is in transit through {@code UPI_CLEARING}, by the day it was collected —
 * endpoints 5 and 6 of design-ledger-api.md §1.2.
 *
 * <h2>⚠ Why "collected on a date" is not the account's balance</h2>
 *
 * {@code acc_account_balance} gives {@code UPI_CLEARING}'s balance, which is the
 * TOTAL in transit — correct, and useless for ageing. §1.2 asks for it "grouped
 * by collection date and aged", because a ₹250 residue from September is a
 * different problem from ₹3,450 collected this afternoon, and only the date
 * separates them. So these are per-{@code VOUCHER_DATE} aggregates rather than
 * reads of the view.
 *
 * <h2>⚠ Collections are the NET of the debits, and settlements are excluded by
 * SOURCE_TYPE</h2>
 *
 * A sale that took UPI debits {@code UPI_CLEARING} and a settlement credits it,
 * and both can carry the same date when money lands the day it was taken. The
 * two must not cancel: "collected on the 30th" has to mean what customers paid,
 * whatever has since arrived from the bank. {@code SOURCE_TYPE <> 'UPI_SETTLEMENT'}
 * is what keeps them apart, and it is a seeded reference code (V21:180) rather
 * than a convention.
 *
 * <p>The sum is still a NET of debits and credits within those vouchers, so a
 * correcting {@code JOURNAL} that credits {@code UPI_CLEARING} reduces the day it
 * is dated. That is deliberate: a correction dated the day the mistake was found
 * (FRD §8.3) then shows as a reduction on that day rather than silently restating
 * a day that has already been settled.
 *
 * <h2>Sealed only</h2>
 *
 * {@code SEALED_AT IS NOT NULL} on every query here, matching V26's view and
 * every report. A voucher interrupted between its three posting steps is
 * invisible rather than half-counted.
 */
public interface UpiClearingRepository extends Repository<AccVoucher, Long> {

    /** {@code V21:291} — the clearing account, {@code ALLOW_MANUAL = FALSE} (A7). */
    String UPI_CLEARING = "UPI_CLEARING";

    /** {@code ref_voucher_source}: 'UPI settled into the bank' (V21:180). */
    String SOURCE_UPI_SETTLEMENT = "UPI_SETTLEMENT";

    /**
     * What customers paid by UPI on each day up to {@code asOf}, newest last.
     *
     * @return rows of {@code [VOUCHER_DATE, NET_COLLECTED]}; a day with no UPI at
     *         all does not appear, rather than appearing at zero
     */
    @Query(value = "SELECT v.VOUCHER_DATE, SUM(l.DR_AMOUNT - l.CR_AMOUNT) "
            + "  FROM acc_voucher_line l "
            + "  JOIN acc_voucher v ON v.VOUCHER_ID = l.VOUCHER_ID "
            + " WHERE l.ACCOUNT_CODE = 'UPI_CLEARING' "
            + "   AND v.SEALED_AT IS NOT NULL "
            + "   AND v.SOURCE_TYPE <> 'UPI_SETTLEMENT' "
            + "   AND v.VOUCHER_DATE <= :asOf "
            + " GROUP BY v.VOUCHER_DATE "
            + " ORDER BY v.VOUCHER_DATE", nativeQuery = true)
    List<Object[]> collectedPerDate(@Param("asOf") LocalDate asOf);

    /**
     * What customers paid by UPI on one day.
     *
     * <p>A separate query rather than a filter over {@link #collectedPerDate},
     * because endpoint 6 needs exactly one day's figure and {@code COALESCE} makes
     * "no UPI that day" arrive as {@code 0} instead of as no row — which is the
     * difference between a 422 that says the day collected nothing and a
     * {@code NullPointerException}.
     */
    @Query(value = "SELECT COALESCE(SUM(l.DR_AMOUNT - l.CR_AMOUNT), 0) "
            + "  FROM acc_voucher_line l "
            + "  JOIN acc_voucher v ON v.VOUCHER_ID = l.VOUCHER_ID "
            + " WHERE l.ACCOUNT_CODE = 'UPI_CLEARING' "
            + "   AND v.SEALED_AT IS NOT NULL "
            + "   AND v.SOURCE_TYPE <> 'UPI_SETTLEMENT' "
            + "   AND v.VOUCHER_DATE = :collectionDate", nativeQuery = true)
    BigDecimal collectedOn(@Param("collectionDate") LocalDate collectionDate);

    /**
     * What the bank has already credited against one collection date, read from
     * the settlement VOUCHERS rather than from the reconciliation row.
     *
     * <p>⚠ <b>THE VOUCHERS ARE THE AUTHORITY AND THE ROW IS THE EVIDENCE</b>, and
     * reading both is what lets endpoint 6 tell them apart.
     * {@code acc_voucher} is append-only once sealed (V23 trigger 1), while
     * {@code acc_reconciliation} is an ordinary row that endpoint 4 can also write
     * for this same account and date. If the two disagree, something other than a
     * settlement reconciled the day, and the settlement refuses rather than
     * accumulating onto a figure it does not own.
     *
     * <p>The match is on the {@code SOURCE_REF} prefix, which is where the
     * published {@code UPI_SETTLEMENT:<collectionDate>:<n>} sequence lives. The
     * trailing {@code ':'} in the prefix matters: without it, collection date
     * {@code 2026-09-3} would also match {@code 2026-09-30}.
     */
    @Query(value = "SELECT COALESCE(SUM(l.CR_AMOUNT - l.DR_AMOUNT), 0) "
            + "  FROM acc_voucher_line l "
            + "  JOIN acc_voucher v ON v.VOUCHER_ID = l.VOUCHER_ID "
            + " WHERE l.ACCOUNT_CODE = 'UPI_CLEARING' "
            + "   AND v.SEALED_AT IS NOT NULL "
            + "   AND v.SOURCE_TYPE = 'UPI_SETTLEMENT' "
            + "   AND v.SOURCE_REF LIKE :refPrefix", nativeQuery = true)
    BigDecimal settledAgainst(@Param("refPrefix") String refPrefix);

    /**
     * How many settlements are already on file for one collection date — the
     * {@code n} in {@code UPI_SETTLEMENT:<collectionDate>:<n>}.
     *
     * <p>⚠ <b>THAT SEQUENCE IS THE WHOLE REASON A PARTIAL SETTLEMENT IS
     * EXPRESSIBLE</b> (§1.2, A4). A key of one-per-date would reject the second
     * call, and the second call is exactly the case the design exists to support:
     * ₹3,450 collected, ₹3,200 lands, ₹250 arrives later.
     */
    @Query(value = "SELECT COUNT(*) FROM acc_voucher v "
            + " WHERE v.SEALED_AT IS NOT NULL "
            + "   AND v.SOURCE_TYPE = 'UPI_SETTLEMENT' "
            + "   AND v.SOURCE_REF LIKE :refPrefix", nativeQuery = true)
    long countSettlements(@Param("refPrefix") String refPrefix);
}
