package com.mangle.retailshopapp.recharge.repo;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.mangle.retailshopapp.recharge.model.RcTxnPayment;
import com.mangle.retailshopapp.recharge.model.RcTxnPaymentId;

/** The tender lines of a basket. */
@Repository
public interface RcTxnPaymentRepository extends JpaRepository<RcTxnPayment, RcTxnPaymentId> {

    @Query("select p from RcTxnPayment p where p.id.txnId = :txnId order by p.id.seqNo asc")
    List<RcTxnPayment> findByTxnId(@Param("txnId") Integer txnId);

    /**
     * The next line number within one basket.
     *
     * <p>Allocated here rather than relying on {@code AUTO_INCREMENT}, because
     * {@code SEQ_NO} is part of a COMPOSITE key on this table and so has no
     * auto-increment of its own — unlike {@code rc_txn_details}, where it is
     * allocated globally (V11 verified 99,038 distinct values over a largest
     * basket of 12 lines).
     */
    @Query("select coalesce(max(p.id.seqNo), 0) from RcTxnPayment p where p.id.txnId = :txnId")
    Short maxSeqNo(@Param("txnId") Integer txnId);

    /**
     * The tender mix for one day (§7.1(c)).
     *
     * <p>Keyed on the header's settled status — a tender on an unsettled basket
     * is not money taken — but on the TENDER LINE's own date, not the header's.
     * §3.6 derives the financial year from the PAYMENT date for grouping, and
     * for every ordinary basket the two are the same instant: the settle path
     * writes {@code h.txnDttm} and {@code p.creDttm} from one {@code now}.
     *
     * <p><b>⚠ THEY COME APART ON EXACTLY ONE SHAPE, AND IT IS WHY THIS CHANGED.</b>
     * A retry at a higher amount appends a tender line to the ORIGINAL payment
     * (log §7.4's "opposite direction"), so a top-up collected on day 2 hangs off
     * a header dated day 1. Dated by the header, that ₹50 would be added to day
     * 1's tender mix while its cash posting landed on day 2 — breaking the
     * cross-check §7.1(c) builds the day summary around ("two INDEPENDENT
     * sources of the same number … if they ever differ, a posting is wrong") on
     * BOTH days, and corrupting a day 1 that may already have been counted
     * against the drawer. Dated by the line, both days agree.
     *
     * <p><b>⚠ A REQUIREMENT THIS PLACES ON V13, which is reserved cutover work
     * and cannot be written here.</b> design-domain.md §6's V13 spec inserts
     * historical {@code CRDT}, {@code UPI} and {@code CASH} lines into this table
     * without naming {@code CRE_DTTM}, which would take V12's
     * {@code DEFAULT CURRENT_TIMESTAMP} — the migration's own run date. Under the
     * old header-dated query that was harmless; under this one it would file
     * 2,850 historical baskets into the tender mix of cutover day. <b>V13 must
     * set {@code CRE_DTTM = h.TXN_DTTM} on every row it inserts.</b> Recorded in
     * {@code db/migration/README.md} as well, where V13's other obligations live.
     */
    @Query("""
            select p.lineType, coalesce(sum(p.amount), 0), count(p)
              from RcTxnPayment p, com.mangle.retailshopapp.credit.model.RcTxnHeader h
             where h.txnId = p.id.txnId
               and h.statusCode = 'SETTLED'
               and p.creDttm >= :dayStart and p.creDttm < :dayEnd
             group by p.lineType
            """)
    List<Object[]> tenderMixOn(@Param("dayStart") LocalDateTime dayStart,
                               @Param("dayEnd") LocalDateTime dayEnd);
}
