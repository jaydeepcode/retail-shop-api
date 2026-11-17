package com.mangle.retailshopapp.accounting.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One purchase of float (V24, design-ledger.md §3.2).
 *
 * <p>A batch records what a specific top-up COST and what FACE value it bought.
 * At the ratio of 1.0 every one of the shop's wallets runs at, the two are equal
 * — the columns are separate because the ratio is a property of the deal, not of
 * the design (FRD §7.2.1's bonus-on-topup is where they diverge).
 *
 * <p>⚠ <b>FACE VALUE IS NOT A GENERAL-LEDGER ACCOUNT, AND THAT IS WHY THIS TABLE
 * EXISTS</b> (§3.1, L6). If face value were a GL asset, a bonus top-up would
 * recognise income BEFORE the sale: pay ₹2,000 for ₹2,060 of face and the balance
 * sheet shows a ₹2,060 asset against a ₹2,000 outflow, with ₹60 having to land
 * somewhere, and the only place it can land is income. FRD §7.2 says the
 * opposite — "cost basis is fixed at topup time; commission is realized
 * proportionally as that batch of float is spent". So the GL carries cost basis
 * and face value lives here.
 *
 * <p>⚠ <b>{@code uk_fbatch_voucher} MAKES ONE VOUCHER FUND AT MOST ONE BATCH</b>,
 * so a double-posted top-up fails at the database rather than silently doubling a
 * wallet's cost basis. {@code ManualVoucherService} checks the idempotency key
 * before it writes anything, so an ordinary replay never reaches that constraint;
 * the constraint is what closes the race between two concurrent first-posts.
 *
 * <p>{@code CRE_DTTM} is left to the column's {@code DEFAULT CURRENT_TIMESTAMP} —
 * it records when the row was written, which is not a figure the application
 * should be choosing. {@code ACQUIRED_ON} is the accounting date and IS chosen:
 * it is the voucher's date, so a top-up entered three days late is valued on the
 * day it happened.
 */
@Getter
@Setter
@Entity
@Table(name = "acc_float_batch")
public class AccFloatBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "BATCH_ID")
    private Long batchId;

    @Column(name = "ACCOUNT_CODE", nullable = false, length = 30)
    private String accountCode;

    /** The {@code PURCHASE} voucher that funded it. Unique. */
    @Column(name = "TOPUP_VOUCHER_ID", nullable = false)
    private Long topupVoucherId;

    @Column(name = "FACE_ADDED", nullable = false, precision = 38, scale = 2)
    private BigDecimal faceAdded;

    /**
     * ⚠ The face value's cost, and <b>NOT including the IMPS fee</b>
     * (log §2.5:114-118). The fee is charged for the transfer method rather than
     * for the balance, so folding it in here would push the ratio off 1.0 and end
     * the portal-versus-books comparison.
     */
    @Column(name = "COST_PAID", nullable = false, precision = 38, scale = 2)
    private BigDecimal costPaid;

    @Column(name = "ACQUIRED_ON", nullable = false)
    private LocalDate acquiredOn;

    /** The portal's own top-up reference, for matching against the vendor. */
    @Column(name = "EXTERNAL_REF", length = 64)
    private String externalRef;

    @Column(name = "NOTE", length = 255)
    private String note;

    @Column(name = "CRE_BY")
    private Integer creBy;

    @Column(name = "CRE_DTTM", insertable = false, updatable = false)
    private LocalDateTime creDttm;
}
