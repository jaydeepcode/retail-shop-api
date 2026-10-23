package com.mangle.retailshopapp.recharge.repo;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.mangle.retailshopapp.recharge.domain.RechargeStatus;
import com.mangle.retailshopapp.recharge.model.RcRecharge;

/**
 * Open items — the worklist and unresolved failures.
 *
 * <p>⚠ NO METHOD HERE TAKES A DATE, AND NONE MAY BE ADDED. That is requirement
 * 9's structural enforcement (design-domain.md §7.1(d)): the financial year is a
 * view filter and must never scope open items. A year filter cannot leak in
 * without someone editing this interface and noticing this comment.
 *
 * <p>Extends Spring Data's bare {@code Repository} rather than
 * {@code JpaRepository} for the same reason — inheriting {@code findAll(Example)}
 * and the rest would hand callers date-capable query methods through the back
 * door.
 *
 * <p>Ordered OLDEST FIRST, because these are chores rather than news (§7.1(d)).
 * And worth knowing before building a queue UI: §14.10 measured 468 of 681 active
 * hours holding exactly one recharge, the busiest hour in six months holding 7,
 * and 89% of recharges having more than five minutes before the next. The
 * worklist is a leftover tray and is normally EMPTY.
 */
public interface RechargeOpenItemsRepository extends Repository<RcRecharge, Integer> {

    /**
     * The worklist: everything awaiting a recharge, optionally narrowed to one
     * operator or route.
     *
     * <p>Null means "any" for both filters, which keeps one query where four
     * derived-name methods would otherwise appear.
     */
    @Query("""
            select r from RcRecharge r
             where r.statusCode = com.mangle.retailshopapp.recharge.domain.RechargeStatus.AWAITING_RECHARGE
               and (:operatorCode is null or r.operatorCode = :operatorCode)
               and (:routeCode    is null or r.routeCode    = :routeCode)
             order by r.rechargeDttm asc
            """)
    Page<RcRecharge> findAwaiting(@Param("operatorCode") String operatorCode,
                                  @Param("routeCode") String routeCode,
                                  Pageable pageable);

    /**
     * Unresolved failures.
     *
     * <p>⚠ ONE STATUS, NOT A PAIR. design-api.md §3.2 endpoint #11 describes this
     * as "{@code status=FAILED, resolution=NONE}", but on this schema a resolved
     * failure has MOVED to {@code RETRIED} or {@code REFUNDED} — §5.2: "no extra
     * {@code IS_RESOLVED} flag". So {@code STATUS_CODE = 'FAILED'} is exactly the
     * unresolved set, and adding a second predicate would be a no-op that implied
     * a column exists.
     */
    @Query("""
            select r from RcRecharge r
             where r.statusCode = com.mangle.retailshopapp.recharge.domain.RechargeStatus.FAILED
             order by r.rechargeDttm asc
            """)
    Page<RcRecharge> findUnresolvedFailures(Pageable pageable);

    /**
     * Everything not in a terminal status, for the end-of-day open exposure
     * COUNT.
     *
     * <p>The AMOUNT held is read from the ledger rather than assembled here: under
     * log §7.2 collected cash sits in {@code RCHG_PENDING} until completion, so
     * open exposure is one account balance. §7.1(c) records that the warning text
     * stays the same and only its source changes — "the figure becomes
     * reconcilable instead of merely displayed".
     */
    @Query("""
            select r.statusCode, count(r), coalesce(sum(r.amount), 0)
              from RcRecharge r
             where r.statusCode in :openStatuses
             group by r.statusCode
            """)
    List<Object[]> countOpenByStatus(@Param("openStatuses") List<RechargeStatus> openStatuses);
}
