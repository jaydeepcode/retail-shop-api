package com.mangle.retailshopapp.credit.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "rc_credit_req")
public class RcCreditReq {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "CRE_REQ_ID")
    private int id;

    @Column(name = "CRED_DTTM", nullable = false)
    private LocalDateTime credDttm;

    @Column(name = "REQ_TYPE", nullable = false)
    private String reqType;

    @Column(name = "REQ_AMT", nullable = false)
    private BigDecimal reqAmt;

    @Column(name = "CUST_ID", nullable = false)
    private int custId;
}

