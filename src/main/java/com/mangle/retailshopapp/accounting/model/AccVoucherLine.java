package com.mangle.retailshopapp.accounting.model;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One leg of a voucher (V22, design-ledger.md §2.3).
 *
 * <p>APPEND-ONLY, enforced by V23's trigger 3, which signals on every UPDATE
 * unconditionally. Nothing in this package ever mutates a persisted instance,
 * and a setter call that reached the database would be refused there — which is
 * the point: historical P&L is a SUM over this table, so an immutable row is
 * what guarantees a reported figure cannot move (§4.1 mechanism 2).
 *
 * <p>Two money columns rather than one signed column (L2): {@code SUM(DR) =
 * SUM(CR)} reads as the accounting invariant it is, and a hand-written report
 * shows debits and credits in the columns a bookkeeper expects with no
 * per-account-type sign rule.
 */
@Getter
@Setter
@Entity
@Table(name = "acc_voucher_line")
public class AccVoucherLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "LINE_ID")
    private Long lineId;

    @Column(name = "VOUCHER_ID", nullable = false)
    private Long voucherId;

    /** 1..n within the voucher; unique per voucher. */
    @Column(name = "LINE_NO", nullable = false)
    private short lineNo;

    @Column(name = "ACCOUNT_CODE", nullable = false, length = 30)
    private String accountCode;

    @Column(name = "DR_AMOUNT", nullable = false, precision = 38, scale = 2)
    private BigDecimal drAmount = BigDecimal.ZERO;

    @Column(name = "CR_AMOUNT", nullable = false, precision = 38, scale = 2)
    private BigDecimal crAmount = BigDecimal.ZERO;

    /** Subledger dimension for AR_* / ADV_* legs. */
    @Column(name = "PARTY_CUST_ID")
    private Integer partyCustId;

    /** CASH | UPI | CRDT on money-in legs (FRD §8.6). */
    @Column(name = "PAYMENT_METHOD", length = 10)
    private String paymentMethod;

    @Column(name = "LINE_NARRATION", length = 255)
    private String lineNarration;
}
