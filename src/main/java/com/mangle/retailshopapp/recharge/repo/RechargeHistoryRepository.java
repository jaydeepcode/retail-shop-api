package com.mangle.retailshopapp.recharge.repo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.mangle.retailshopapp.recharge.domain.RechargeStatus;
import com.mangle.retailshopapp.recharge.model.RcRecharge;

/**
 * History and the day summary — the only reads a financial year may scope.
 *
 * <p>⚠ EVERY METHOD HERE TAKES {@code fyStart}/{@code fyEnd} AS REQUIRED
 * PARAMETERS, and none is {@code @Nullable}. The mirror of
 * {@link RechargeOpenItemsRepository}: a year cannot be forgotten on history any
 * more than it can leak into open items (design-domain.md §7.1(d)).
 *
 * <p>The range is HALF-OPEN — {@code >= fyStart AND < fyEnd} — never
 * {@code BETWEEN}, so the 31-March-23:59:59.999 boundary can be neither lost nor
 * double-counted, and the predicate stays SARGable against
 * {@code idx_rech_dttm_status} (§9.2). No expression ever wraps the indexed
 * column: {@code YEAR(RECHARGE_DTTM)} would force a full scan.
 *
 * <p>⚠ HISTORY IS NEW SALES ONLY (log §15.11). The owner settled this on
 * 2026-10-12: "only new sales is fine for recharge data". So there is no
 * {@code UNION} with {@code rc_txn_details} anywhere here, and V15's backfill
 * stays a one-off migration serving the cutover rather than a permanent read
 * path. Consequence the SCREEN must handle: {@code rc_recharge} is empty until
 * V15 runs, so this returns nothing on day one and fills only as new sales are
 * taken. That is correct, not a bug — but it needs an empty state that says so,
 * or it reads as broken.
 */
public interface RechargeHistoryRepository extends Repository<RcRecharge, Integer> {

    /**
     * History search (§7.1(a)).
     *
     * <p>⚠ THE NUMBER MATCH IS A PREFIX, NOT A CONTAINS. {@code LIKE '%1234%'}
     * cannot use {@code idx_rech_number} and degrades to a full scan. §7.1(a)
     * notes that at 52k rows that is still only ~50ms, so a contains mode is
     * AFFORDABLE — "but it must be a deliberate, separate, slower mode, not the
     * default."
     *
     * <p>And name is deliberately NOT a filter here: 9,776 distinct recharged
     * mobile numbers against 244 matching a customer means most recharges have no
     * name at all. §7.1(a) puts name search on TV connections and credit sales,
     * as a separate tab.
     */
    @Query("""
            select r from RcRecharge r
             where r.rechargeDttm >= :fyStart and r.rechargeDttm < :fyEnd
               and (:operatorCode is null or r.operatorCode = :operatorCode)
               and (:routeCode    is null or r.routeCode    = :routeCode)
               and (:status       is null or r.statusCode   = :status)
               and (:staffId      is null or r.creBy        = :staffId)
               and (:numberPrefix is null or r.targetNumber like concat(:numberPrefix, '%'))
               and (:externalRef  is null or r.externalRef  = :externalRef)
             order by r.rechargeDttm desc
            """)
    Page<RcRecharge> search(@Param("fyStart") LocalDateTime fyStart,
                            @Param("fyEnd") LocalDateTime fyEnd,
                            @Param("operatorCode") String operatorCode,
                            @Param("routeCode") String routeCode,
                            @Param("status") RechargeStatus status,
                            @Param("staffId") Integer staffId,
                            @Param("numberPrefix") String numberPrefix,
                            @Param("externalRef") String externalRef,
                            Pageable pageable);

    /** The payment ids on one page of history, so their recharges can be nested. */
    @Query("""
            select distinct r.txnId from RcRecharge r
             where r.rechargeDttm >= :fyStart and r.rechargeDttm < :fyEnd
             order by r.txnId desc
            """)
    List<Integer> paymentIdsIn(@Param("fyStart") LocalDateTime fyStart,
                               @Param("fyEnd") LocalDateTime fyEnd,
                               Pageable pageable);

    /**
     * The day's takings by operator and route.
     *
     * <p>⚠ {@code COMPLETED} ONLY, which is the rule that makes {@code VOIDED}
     * work at all: voiding a sale drops it out of takings for free, because this
     * predicate already filters on the status (§5.2, §7.1(c)).
     *
     * <p>A consequence worth stating on the screen rather than hiding: because
     * takings count only completed recharges while {@code txn_total_amt} includes
     * failed ones, CASH IN THE DRAWER WILL NOT EQUAL TAKINGS whenever a recharge
     * has failed and not yet been refunded. That is correct — the money is held
     * pending — and it is exactly what the end-of-day check should warn about.
     * §7.1(c): the warning says "₹X held against N unresolved recharges", not
     * "till mismatch".
     */
    @Query("""
            select r.operatorCode, r.routeCode, count(r), coalesce(sum(r.amount), 0),
                   coalesce(sum(r.surcharge), 0)
              from RcRecharge r
             where r.statusCode = com.mangle.retailshopapp.recharge.domain.RechargeStatus.COMPLETED
               and r.rechargeDttm >= :dayStart and r.rechargeDttm < :dayEnd
             group by r.operatorCode, r.routeCode
            """)
    List<Object[]> takingsByOperatorAndRoute(@Param("dayStart") LocalDateTime dayStart,
                                             @Param("dayEnd") LocalDateTime dayEnd);

    /** The financial years that actually hold data, for the picker (§9.2). */
    @Query("select min(r.rechargeDttm), max(r.rechargeDttm) from RcRecharge r")
    List<Object[]> dataDateBounds();

    /** One day's completed surcharge, which is its own income line. */
    @Query("""
            select coalesce(sum(r.surcharge), 0) from RcRecharge r
             where r.statusCode = com.mangle.retailshopapp.recharge.domain.RechargeStatus.COMPLETED
               and r.rechargeDttm >= :dayStart and r.rechargeDttm < :dayEnd
            """)
    BigDecimal surchargeOn(@Param("dayStart") LocalDateTime dayStart,
                           @Param("dayEnd") LocalDateTime dayEnd);
}
