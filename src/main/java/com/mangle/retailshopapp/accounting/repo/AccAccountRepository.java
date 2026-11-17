package com.mangle.retailshopapp.accounting.repo;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.mangle.retailshopapp.accounting.model.AccAccount;

@Repository
public interface AccAccountRepository extends JpaRepository<AccAccount, String> {

    List<AccAccount> findByAccountCodeIn(Collection<String> accountCodes);

    /**
     * Everything the close screen can offer tonight (endpoint 1).
     *
     * <p>{@code IS_RECONCILABLE} is the flag that says an account has an
     * independent source to count against — a cash drawer, a bank statement, an
     * operator portal. {@code LedgerCloseService} partitions the result by
     * {@code CASH_POOL}: a member of a real pool closes through endpoint 2, and
     * everything else through endpoint 4.
     */
    List<AccAccount> findByReconcilableTrueAndActiveTrueOrderBySortOrder();

    /** One pool's members, in display order. The primary is found by its flag. */
    List<AccAccount> findByCashPoolAndActiveTrueOrderBySortOrder(String cashPool);

    /**
     * The picker list (endpoint 12), with §1.3's three optional filters.
     *
     * <p>⚠ ONE QUERY WITH NULLABLE PARAMETERS RATHER THAN A {@code Specification}
     * OR FOUR FINDERS. Three independent optional filters are eight combinations,
     * and the derived-name alternative —
     * {@code findByActiveTrueAndAllowManualTrueAndAccountTypeAndBusinessCode…} —
     * needs one method per combination, which is how two of them end up with
     * different ordering. {@code Specification} would mean a second repository
     * interface for three predicates.
     *
     * <p>{@code IS_ACTIVE} is NOT a filter and is not negotiable: §1.3 says
     * "active accounts", and an inactive account offered in a picker is a leg
     * {@code LedgerPostingService} refuses at post time ("account is not active").
     * {@code SARAVATE} is retired and must never appear.
     *
     * <p>⚠ {@code manualOnly} TAKES A {@code boolean} AND NOT A {@code Boolean}:
     * {@code manualOnly=false} and the parameter omitted mean the same thing —
     * show everything — and a tri-state would invite a third meaning nobody has
     * defined. Filtering to {@code ALLOW_MANUAL = FALSE} is not a thing a picker
     * wants; the machine-owned accounts are reached through the endpoints that
     * own them, never picked.
     *
     * @param manualOnly true to return only what §1.3's generic voucher endpoints
     *                   will accept (A7)
     * @param type       {@code ASSET} | {@code LIABILITY} | {@code INCOME} |
     *                   {@code EXPENSE} | {@code EQUITY}, or null for all
     * @param business   {@code WATER} | {@code RECHARGE} | {@code TICKET} |
     *                   {@code SHARED}, or null for all
     */
    @Query("SELECT a FROM AccAccount a WHERE a.active = true "
            + "AND (:manualOnly = false OR a.allowManual = true) "
            + "AND (:type IS NULL OR a.accountType = :type) "
            + "AND (:business IS NULL OR a.businessCode = :business) "
            + "ORDER BY a.sortOrder")
    List<AccAccount> findDirectory(@Param("manualOnly") boolean manualOnly,
                                   @Param("type") String type,
                                   @Param("business") String business);
}
