package com.mangle.retailshopapp.recharge.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.mangle.retailshopapp.recharge.domain.RechargeStatus;
import com.mangle.retailshopapp.recharge.model.RcRecharge;

import jakarta.persistence.LockModeType;

/**
 * Writes and single-row reads on {@code rc_recharge}.
 *
 * <p>Lists live on the two split repositories in this package — see its
 * {@code package-info} for why that split is enforcement rather than style.
 */
@Repository
public interface RcRechargeRepository extends JpaRepository<RcRecharge, Long> {

    /**
     * Re-reads a recharge for update, holding a row lock until the transaction
     * ends (design-domain.md §5.4 step 1).
     *
     * <p>⚠ THIS IS THE MODULE'S ONLY LOCK, AND IT IS DOING TWO JOBS. §5.4 takes
     * it so that two staff hitting the same failed recharge cannot both resolve
     * it; log §8.1 then moved the float availability check to the completion
     * moment and noted "the existing pessimistic lock also serialises concurrent
     * completion attempts for free". So one {@code SELECT ... FOR UPDATE} covers
     * both the status transition and the float read that depends on it.
     *
     * <p>It is also why there is no {@code @Version} column: design-api.md §3.3
     * shows one and §3.2 lists a 409 version conflict, but the schema has no such
     * column and two locking strategies on one row would be worse than either.
     *
     * <p>Note what §4.4 forbids a pessimistic lock FOR: the customer's balance
     * shown at basket time and the credit row written at settle time are separate
     * transactions, and that is fine — the balance is advisory, there is no credit
     * limit to enforce, so there is nothing to check-then-act on. "Do not add a
     * pessimistic lock here."
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RcRecharge r where r.rechargeId = :id")
    Optional<RcRecharge> findByIdForUpdate(@Param("id") Long id);

    /** Every recharge settled by one payment, in the order they were entered. */
    List<RcRecharge> findByTxnIdOrderBySeqNoAsc(Integer txnId);

    /**
     * The retry chain off one failed recharge.
     *
     * <p>At most one may exist. §5.3 backs that with a database unique key on
     * {@code FAILED_RECHARGE_ID}; this is how the service gives a sentence before
     * the constraint gives a violation.
     */
    List<RcRecharge> findByFailedRechargeId(Long failedRechargeId);

    /**
     * The prefill hint: what this number was last recharged with.
     *
     * <p>⚠ EARNS ITS PLACE ON MEASUREMENT (log §14.10): 88.5% of mobile and 93.6%
     * of TV recharges go to a number seen before, and the operator differs from
     * that number's last recharge on only 8.8% of mobile repeats and 3.4% of TV.
     * Unbounded by date on purpose — a number recharged once a year is still a
     * number seen before, and this is not a history view.
     */
    Optional<RcRecharge> findFirstByTargetNumberAndRechargeKindOrderByRechargeDttmDesc(
            String targetNumber, com.mangle.retailshopapp.recharge.domain.ServiceType rechargeKind);

    /**
     * The §2.2(3) invariant MySQL cannot hold: at most one non-terminal attempt
     * per item line.
     *
     * <p>Asserted in the service after a transition (§5.4 step 5), because it
     * spans rows and {@code CHECK} cannot see other rows.
     */
    @Query("""
            select count(r) from RcRecharge r
             where r.txnId = :txnId and r.seqNo = :seqNo
               and r.statusCode in :openStatuses
            """)
    long countOpenAttemptsOnLine(@Param("txnId") Integer txnId,
                                 @Param("seqNo") Integer seqNo,
                                 @Param("openStatuses") List<RechargeStatus> openStatuses);
}
