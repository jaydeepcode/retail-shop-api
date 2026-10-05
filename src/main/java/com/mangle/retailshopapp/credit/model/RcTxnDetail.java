package com.mangle.retailshopapp.credit.model;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Entity
@Table(name = "rc_txn_details")
public class RcTxnDetail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "SEQ_NO")
    private int seqNo;

    @Column(name = "TXN_FLG", nullable = false)
    private String txnFlg;

    @Column(name = "REFERENCE_NO")
    private String referenceNo;

    @Column(name = "AMOUNT")
    private BigDecimal amount;

    @Column(name = "REF_COMPANY")
    private String refCompany;

    @ManyToOne
    @JoinColumn(name = "TXN_ID", nullable = false)
    private RcTxnHeader rcTxnHeader;
}
