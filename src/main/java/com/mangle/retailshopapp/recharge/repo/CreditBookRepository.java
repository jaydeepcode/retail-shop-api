package com.mangle.retailshopapp.recharge.repo;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.mangle.retailshopapp.credit.model.RcCreditReq;

/**
 * Who owes the shop money — across EVERY business (log §14.8, design-api.md §3.2).
 *
 * <p>⚠ NO DATE PARAMETER ANYWHERE, for the same structural reason as
 * {@link RechargeOpenItemsRepository}: balances are open items and requirement 9
 * forbids scoping them by year. §9.3 names the concrete failure — the first time
 * a year reaches this query, 33 of 34 open balances vanish and staff stop chasing
 * debt.
 *
 * <p><b>⚠ CROSS-BUSINESS, which reverses what design-api.md §3.2 said before.</b>
 * It previously scoped the credit book to recharge, calling it "the counter's fast
 * read". That justification does not survive the numbers — 506 customers and
 * 2,844 credit rows, where §12's own register says no index is needed for a
 * 506-row table, so there is no speed problem to trade correctness against. And
 * scoping does harm: someone owing ₹2,000 on water and ₹49 on recharge would show
 * as ₹49, so the warning that is the entire reason the panel exists would
 * understate the exposure while looking authoritative. The {@code AR_*} accounts
 * stay split in the ledger so debt remains attributable; no screen shows that
 * split.
 *
 * <p><b>⚠ WRITTEN NATIVE, AND NOT BY EXTENDING
 * {@code RcCreditReqRepository.findTransactionHistory}.</b> §7.1(b) gives two
 * reasons and both are real:
 *
 * <ul>
 *   <li>That query computes {@code CASE WHEN r.reqType='DBT' THEN r.reqAmt * -1},
 *       and {@code DBT} amounts are ALREADY NEGATIVE in the data, so the
 *       {@code * -1} yields a positive "credit" figure. §7.1(b) is careful here:
 *       that may be a deliberate display convention or a sign bug, and "it is
 *       genuinely ambiguous from the code alone". Either way it is not a
 *       foundation to build a balance on.
 *   <li>Its window is {@code ORDER BY r.credDttm} with NO {@code PARTITION BY},
 *       which is safe only because it is pre-filtered to one customer. A
 *       credit-book version must partition, or every customer's running balance
 *       would accumulate the one before.
 * </ul>
 */
public interface CreditBookRepository extends Repository<RcCreditReq, Integer> {

    /**
     * The credit book, oldest debt first.
     *
     * <p>"Oldest debt" is the date the CURRENTLY outstanding debt began, which
     * with a single-sided tally and no per-charge allocation means FIFO
     * consumption: the last moment the running balance was clear. That is what
     * {@code zero_point} finds.
     *
     * <p>⚠ {@code balance <> 0}, NOT {@code balance < 0}. Two customers are IN
     * CREDIT (+₹37 and +₹15), and §7.1(b) warns that a "who owes us" filter of
     * {@code < 0} "silently loses them". §14.2 records what that costs: the same
     * ₹52 counted twice in opposite directions inside a sealed opening journal,
     * with {@code OWNERS_EQUITY} absorbing the difference as residual.
     *
     * <p>Columns, in order: {@code CUST_ID}, first name, last name, contact,
     * {@code balance} (negative means owed), last activity, oldest open datetime.
     */
    @Query(value = """
            WITH run AS (
                SELECT CUST_ID, CRED_DTTM, CRE_REQ_ID, req_amt,
                       SUM(req_amt) OVER (PARTITION BY CUST_ID
                                          ORDER BY CRED_DTTM, CRE_REQ_ID) AS running_bal
                  FROM rc_credit_req
            ),
            zero_point AS (
                SELECT CUST_ID, MAX(CRED_DTTM) AS cleared_at
                  FROM run WHERE running_bal >= 0 GROUP BY CUST_ID
            ),
            tally AS (
                SELECT CUST_ID, SUM(req_amt) AS balance, MAX(CRED_DTTM) AS last_activity
                  FROM rc_credit_req GROUP BY CUST_ID
            )
            SELECT t.CUST_ID, c.FIRST_NAME, c.LAST_NAME, c.CONTACT_NUM,
                   t.balance, t.last_activity,
                   (SELECT MIN(r2.CRED_DTTM) FROM run r2
                     WHERE r2.CUST_ID = t.CUST_ID
                       AND (z.cleared_at IS NULL OR r2.CRED_DTTM > z.cleared_at)) AS oldest_open
              FROM tally t
              JOIN rs_cust_dtls c ON c.CUST_ID = t.CUST_ID
              LEFT JOIN zero_point z ON z.CUST_ID = t.CUST_ID
             WHERE t.balance <> 0
               AND t.CUST_ID NOT IN (:ledgerCustomerIds)
             ORDER BY oldest_open ASC
            """, nativeQuery = true)
    List<Object[]> creditBook(@Param("ledgerCustomerIds") Collection<Integer> ledgerCustomerIds,
                              Pageable pageable);

    /**
     * One customer's netted position and the date their open debt began.
     *
     * <p>The same arithmetic as {@link #creditBook}, for one person, so the
     * figure a staff member sees on the basket screen is computed the same way as
     * the figure on the credit book. Two formulas for one number is how they come
     * to disagree.
     */
    @Query(value = """
            WITH run AS (
                SELECT CRED_DTTM, CRE_REQ_ID,
                       SUM(req_amt) OVER (ORDER BY CRED_DTTM, CRE_REQ_ID) AS running_bal
                  FROM rc_credit_req WHERE CUST_ID = :custId
            ),
            zero_point AS (
                SELECT MAX(CRED_DTTM) AS cleared_at FROM run WHERE running_bal >= 0
            )
            SELECT (SELECT COALESCE(SUM(req_amt), 0) FROM rc_credit_req WHERE CUST_ID = :custId),
                   (SELECT MIN(r2.CRED_DTTM) FROM run r2, zero_point z
                     WHERE z.cleared_at IS NULL OR r2.CRED_DTTM > z.cleared_at)
            """, nativeQuery = true)
    List<Object[]> positionOf(@Param("custId") Integer custId);

    /**
     * One customer's statement, newest first, with a running balance.
     *
     * <p>Every line from ANY business (§3.2 endpoint #15), with who extended each
     * credit. The running balance is computed OLDEST-first in the window and the
     * rows are then returned newest-first, which is why the ordering appears
     * twice — reversing a running total is not the same as computing it
     * backwards.
     *
     * <p>Columns: {@code CRE_REQ_ID}, {@code CRED_DTTM}, {@code req_type},
     * {@code req_amt}, running balance, {@code NOTE}, extender's username,
     * {@code TXN_ID}.
     */
    @Query(value = """
            SELECT x.CRE_REQ_ID, x.CRED_DTTM, x.req_type, x.req_amt, x.running_bal,
                   x.NOTE, u.user_name, x.TXN_ID
              FROM (
                SELECT r.CRE_REQ_ID, r.CRED_DTTM, r.req_type, r.req_amt, r.NOTE,
                       r.CRE_BY, r.TXN_ID,
                       SUM(r.req_amt) OVER (ORDER BY r.CRED_DTTM, r.CRE_REQ_ID) AS running_bal
                  FROM rc_credit_req r
                 WHERE r.CUST_ID = :custId
              ) x
              LEFT JOIN rc_user u ON u.id = x.CRE_BY
             ORDER BY x.CRED_DTTM DESC, x.CRE_REQ_ID DESC
            """, nativeQuery = true)
    List<Object[]> statementOf(@Param("custId") Integer custId, Pageable pageable);

    /** How many customers carry a non-zero tab, for the page count. */
    @Query(value = """
            SELECT COUNT(*) FROM (
                SELECT CUST_ID, SUM(req_amt) AS balance
                  FROM rc_credit_req GROUP BY CUST_ID
            ) t
             WHERE t.balance <> 0 AND t.CUST_ID NOT IN (:ledgerCustomerIds)
            """, nativeQuery = true)
    long countCreditBook(@Param("ledgerCustomerIds") Collection<Integer> ledgerCustomerIds);
}
