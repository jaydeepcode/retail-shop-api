package com.mangle.retailshopapp.credit.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "rc_txn_header")
public class RcTxnHeader {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "TXN_ID")
    private int txnId;

    @Column(name = "TXN_DTTM", nullable = false)
    private LocalDateTime txnDttm;

    @Column(name = "TXN_TOTAL_AMT", nullable = false)
    private BigDecimal txnTotalAmt;

    @Column(name = "AMT_TNDRED", nullable = false)
    private BigDecimal amtTndred;

    @Column(name = "AMT_RETURNED", nullable = false)
    private BigDecimal amtReturned = BigDecimal.ZERO;

    /**
     * The payment lifecycle (V11, design-domain.md §4.1, §5.3).
     *
     * <p>⚠ DEFAULTED TO {@code SETTLED} IN THE FIELD, not only in the column, and
     * that is load-bearing rather than tidy. The column is
     * {@code NOT NULL DEFAULT 'SETTLED'} so that V11's ALTER backfills all 73,469
     * historical headers in one step — but Hibernate names every mapped column in
     * its INSERT, so a null here would send an explicit NULL and the database
     * default would never apply. {@code WaterTransactionService} writes headers
     * through this entity without knowing the column exists, and a water sale IS
     * settled the moment it is written.
     *
     * <p>The counter sets it explicitly, including {@code AWAITING_PAYMENT}.
     */
    @Column(name = "STATUS_CODE", nullable = false, length = 20)
    private String statusCode = "SETTLED";

    /**
     * The staff member who took the sale (V11, §8.8).
     *
     * <p>Nullable, and it STAYS NULL for all 73,469 historical rows: no record of
     * who served a legacy sale exists anywhere, so there is nothing to backfill
     * from.
     */
    @Column(name = "CRE_BY")
    private Integer creBy;

    /**
     * The named customer a credit tender requires (V11, §5.3).
     *
     * <p>The rule "a credit line implies a named customer" spans two tables —
     * {@code rc_txn_payment.LINE_TYPE='CRDT'} implies this is non-null — which
     * MySQL {@code CHECK} cannot express. §5.3 recommends the service check plus
     * a reconciliation query over an invisible trigger, and states the trade
     * honestly: hand-written SQL can break this rule.
     */
    @Column(name = "CUST_ID")
    private Integer custId;

    @OneToMany(mappedBy = "rcTxnHeader", cascade = CascadeType.ALL) 
    private List<RcTxnDetail> txnDetails;
}
