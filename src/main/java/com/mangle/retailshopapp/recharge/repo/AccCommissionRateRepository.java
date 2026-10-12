package com.mangle.retailshopapp.recharge.repo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.mangle.retailshopapp.recharge.model.AccCommissionRate;

/**
 * The rate lookup (design-ledger.md §4.2).
 *
 * <p>Read-only in v1: a rate is never edited, and nothing in S3 inserts one.
 * Extends the bare {@code Repository} so {@code save} and {@code delete} are not
 * inherited — §4.1's "closed and superseded, never edited" is enforced by
 * {@code LedgerCorrectionRules}, and an unused {@code save} here would be a way
 * around it.
 */
public interface AccCommissionRateRepository extends Repository<AccCommissionRate, Long> {

    /**
     * The one rate that applies, or nothing.
     *
     * <p>⚠ {@code :rechargeDate}, NOT {@code CURDATE()}. §4.2 is explicit: a
     * recharge entered a day late must use the rate that applied on the day it
     * HAPPENED. Using today's date would silently re-price yesterday's sale.
     *
     * <p>The {@code ORDER BY} is what makes the lookup deterministic even if two
     * rows overlap. MySQL has no exclusion constraint and {@code uk_rate_key}
     * only stops exact duplicates, so overlap is possible by hand-SQL; §4.2 calls
     * that "a named limitation, not a solved problem" and V27 reports any that
     * appear. The precedence is: an exact route beats the wildcard, then an exact
     * tier, then the narrowest band containing the amount, then the most recently
     * effective.
     *
     * <p>Returns a list with {@code Pageable} of size 1 rather than
     * {@code Optional}, because the deterministic ordering is the whole guarantee
     * and {@code LIMIT 1} has to be part of the query rather than applied after.
     */
    @Query("""
            select r from AccCommissionRate r
             where r.operatorCode = :operatorCode
               and r.routeCode in (:routeCode, '*')
               and r.planTier in (:planTier, '*')
               and :amount >= r.minAmount
               and (r.maxAmount is null or :amount < r.maxAmount)
               and r.effectiveFrom <= :rechargeDate
               and (r.effectiveTo is null or r.effectiveTo > :rechargeDate)
             order by case when r.routeCode = '*' then 1 else 0 end asc,
                      case when r.planTier  = '*' then 1 else 0 end asc,
                      r.minAmount desc,
                      r.effectiveFrom desc
            """)
    List<AccCommissionRate> lookup(@Param("operatorCode") String operatorCode,
                                   @Param("routeCode") String routeCode,
                                   @Param("planTier") String planTier,
                                   @Param("amount") BigDecimal amount,
                                   @Param("rechargeDate") LocalDate rechargeDate,
                                   Pageable oneRow);
}
