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
import lombok.Data;

@Data
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

    @OneToMany(mappedBy = "rcTxnHeader", cascade = CascadeType.ALL) 
    private List<RcTxnDetail> txnDetails;
}
