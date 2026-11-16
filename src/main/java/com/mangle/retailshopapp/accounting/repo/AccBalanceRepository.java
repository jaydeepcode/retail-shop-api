package com.mangle.retailshopapp.accounting.repo;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.mangle.retailshopapp.accounting.model.AccAccount;

/**
 * Derived balances, read from the ledger's own view.
 *
 * <p>⚠ IT READS {@code acc_account_balance}, IT DOES NOT RECOMPUTE, for the
 * reason {@code recharge/repo/AccAccountBalanceRepository} spells out at length:
 * §2.6's published query put the seal filter in a {@code LEFT JOIN}'s {@code ON}
 * clause while every {@code SUM} reads the LINE table, so unsealed vouchers were
 * counted in every balance, and V26 is where that is fixed. A second hand-rolled
 * balance query would be a second chance to get it wrong, and the two would
 * disagree silently.
 *
 * <p><b>Why a second repository and not that one.</b> It lives in
 * {@code recharge/repo} and is scoped, by its own javadoc, to what the recharge
 * day summary needs — one account at a time, plus two per-date movement queries.
 * The close reads every pool member and every single reconcilable account in one
 * screen, and {@code accounting} importing from {@code recharge} would be a
 * module dependency pointing the wrong way, which is the objection that moved
 * the error vocabulary to {@code apierror} in the first place
 * (next-session-s5.md item 2).
 */
public interface AccBalanceRepository extends Repository<AccAccount, String> {

    /**
     * Each named account's signed balance in its normal direction, as the view
     * reports it.
     *
     * <p>⚠ ALL-TIME, NOT BOUNDED BY {@code reconDate}, and that is correct rather
     * than lazy. The counted figure is what is physically in the drawer at the
     * moment of counting, so the figure it is compared against has to be the
     * balance at that same moment — and because the sweep leaves every till at
     * zero (§1.1 step 5), the all-time balance IS the takings since the last
     * close. That is what {@code lastClosedAt} means by "the left edge of
     * {@code expectedAmount}".
     *
     * @return rows of {@code [ACCOUNT_CODE, BALANCE]}; an account with no
     *         postings still appears, at {@code 0.00}
     */
    @Query(value = "SELECT ACCOUNT_CODE, BALANCE FROM acc_account_balance "
            + "WHERE ACCOUNT_CODE IN (:codes)", nativeQuery = true)
    List<Object[]> balancesOf(@Param("codes") Collection<String> codes);

    /**
     * A float's face-side ledger figure: {@code SUM(FACE_DELTA)} over
     * {@code acc_float_movement} (§3, V24:98).
     *
     * <p>Kept apart from the cost-side balance above on purpose. The float's
     * cost/face ratio is exactly 1.0 for all four of the shop's wallets (log
     * §2.4:97-99), so comparing the portal against the books is a straight
     * comparison — and that comparison is the only independent check the design
     * has on float, which is why §2.5 keeps the ₹5 IMPS fee out of the cost basis
     * and why this figure is recorded rather than assumed equal.
     */
    @Query(value = "SELECT COALESCE(SUM(FACE_DELTA), 0) FROM acc_float_movement "
            + "WHERE ACCOUNT_CODE = :code", nativeQuery = true)
    BigDecimal faceLedgerOf(@Param("code") String code);

}
