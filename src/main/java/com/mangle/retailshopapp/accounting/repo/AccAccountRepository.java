package com.mangle.retailshopapp.accounting.repo;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
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
}
