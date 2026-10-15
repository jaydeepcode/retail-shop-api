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
     * <p>Keyed on the HEADER's settled status and date rather than on the
     * recharge's, because a basket is paid for at one moment while its recharges
     * complete later — possibly after midnight. §3.6: the financial year, and by
     * the same logic the day, is derived from the PAYMENT date for grouping.
     */
    @Query("""
            select p.lineType, coalesce(sum(p.amount), 0), count(p)
              from RcTxnPayment p, com.mangle.retailshopapp.credit.model.RcTxnHeader h
             where h.txnId = p.id.txnId
               and h.statusCode = 'SETTLED'
               and h.txnDttm >= :dayStart and h.txnDttm < :dayEnd
             group by p.lineType
            """)
    List<Object[]> tenderMixOn(@Param("dayStart") LocalDateTime dayStart,
                               @Param("dayEnd") LocalDateTime dayEnd);
}
