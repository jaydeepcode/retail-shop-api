package com.mangle.retailshopapp.recharge.repo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.mangle.retailshopapp.accounting.model.AccAccount;

/**
 * Reads one account's balance from the ledger's own view.
 *
 * <p>⚠ IT READS {@code acc_account_balance}, IT DOES NOT RECOMPUTE. That view
 * (V26) is the only correct place this arithmetic lives, and the reason is a
 * defect S2 found by executing: §2.6's published query put the seal filter in a
 * {@code LEFT JOIN}'s {@code ON} clause while every {@code SUM} reads the LINE
 * table, so the predicate only null-extended columns nothing reads and unsealed
 * vouchers were counted in every balance. Moving it to {@code WHERE} is not the
 * fix either — that turns the outer join inner and drops every account with no
 * postings, which on day one is every account. The seal test has to sit inside
 * the aggregate, which is what V26 does.
 *
 * <p>So a second hand-rolled balance query in this module would be a second
 * chance to get that wrong, and the two would disagree silently.
 *
 * <p>Scoped to what the day summary needs rather than generalised: S5 owns
 * reporting, and a full balance API here would be built twice.
 */
public interface AccAccountBalanceRepository extends Repository<AccAccount, String> {

    /**
     * One account's signed balance, in its normal direction.
     *
     * <p>{@code RCHG_PENDING} is a liability, so its normal balance is credit and
     * a positive figure means money is held. §7.1(c): under log §7.2 the open
     * exposure the end-of-day check warns about IS this balance — "the figure
     * becomes reconcilable instead of merely displayed" — rather than something
     * assembled from status joins.
     */
    @Query(value = "SELECT BALANCE FROM acc_account_balance WHERE ACCOUNT_CODE = :code",
           nativeQuery = true)
    List<BigDecimal> balanceOf(@Param("code") String code);

    /**
     * One account's NET MOVEMENT on one accounting date, debits less credits.
     *
     * <p>⚠ THIS IS WHAT THE DRAWER ACTUALLY RECONCILES AGAINST, and the view
     * cannot answer it: {@code acc_account_balance} is an all-time aggregate, so
     * it reports every rupee that ever passed through {@code CASH_RECHARGE}
     * rather than today's.
     *
     * <p>Keyed on {@code VOUCHER_DATE} — the ACCOUNTING date — and not on
     * {@code SEALED_AT}, which is merely when the row was written. A back-dated
     * correction belongs to the day it is dated for, which is the whole reason
     * §2.3 keeps the two columns apart.
     *
     * <p>Sealed vouchers only, for the reason V26's header spells out at length:
     * an unsealed voucher is a half-written one, and counting it is the defect S2
     * found in §2.6's published query.
     */
    @Query(value = "SELECT COALESCE(SUM(l.DR_AMOUNT) - SUM(l.CR_AMOUNT), 0) "
            + "  FROM acc_voucher_line l "
            + "  JOIN acc_voucher v ON v.VOUCHER_ID = l.VOUCHER_ID "
            + " WHERE v.SEALED_AT IS NOT NULL "
            + "   AND v.VOUCHER_DATE = :day "
            + "   AND l.ACCOUNT_CODE = :code", nativeQuery = true)
    BigDecimal movementOn(@Param("code") String code, @Param("day") LocalDate day);
}
