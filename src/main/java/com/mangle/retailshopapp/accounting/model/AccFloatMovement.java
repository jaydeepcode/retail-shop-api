package com.mangle.retailshopapp.accounting.model;

import java.math.BigDecimal;
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
 * The append-only signed-delta log behind a float's valuation (V24,
 * design-ledger.md §3.2).
 *
 * <p>⚠ <b>SIGNED DELTAS ARE THE WHOLE DESIGN.</b> Because every event appends a
 * row and both money columns are signed, one expression is automatically the
 * running weighted average after any number of top-ups at any number of prices:
 * {@code SUM(COST_DELTA) / NULLIF(SUM(FACE_DELTA), 0)}. There is no running field
 * to keep in step, which is why §3.4 can say the weighted average is derived and
 * never stored.
 *
 * <p>⚠ <b>APPEND-ONLY IS ENFORCED BY A TRIGGER, NOT BY CONVENTION</b>
 * ({@code trg_acc_float_movement_bu}): every {@code UPDATE} raises
 * {@code SQLSTATE 45000}. The running average is a {@code SUM} over these rows,
 * so a mutable row would silently restate the cost basis of every sale after it.
 * Nothing here ever updates one.
 *
 * <p>⚠ <b>WHAT IS AND IS NOT WRITTEN TODAY, STATED RATHER THAN DISCOVERED.</b>
 * Endpoint 19 appends a {@code TOPUP} row per top-up. <b>Recharge sales do not
 * append a {@code SALE} row</b> — {@code RechargeLedgerPostings} posts the GL legs
 * and short-circuits the weighted average to the constant 1.0, which its own
 * comment explains is both correct and safer while every wallet is at parity
 * (§3.5: the machinery is "dormant, not dead"). The consequence is specific and
 * is not introduced here: {@code acc_float_movement} records what was BOUGHT and
 * not what was SPENT, so {@code SUM(FACE_DELTA)} — which endpoint 4 reports as a
 * float close's {@code faceLedger} — is the total ever topped up rather than the
 * balance the portal shows. Before this endpoint existed that figure was
 * {@code 0.00} for the same reason, which is why
 * {@code LedgerCloseServiceTest} asserts it as "no movements exist yet". The face
 * side becomes a true comparison when the recharge module appends its
 * consumption, which is that module's work; the cost side is correct today,
 * because the GL carries it.
 */
@Getter
@Setter
@Entity
@Table(name = "acc_float_movement")
public class AccFloatMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "MOVEMENT_ID")
    private Long movementId;

    @Column(name = "ACCOUNT_CODE", nullable = false, length = 30)
    private String accountCode;

    /** {@code ref_float_movement_type}: TOPUP | SALE | REVERSAL | ADJUST. */
    @Column(name = "MOVEMENT_TYPE", nullable = false, length = 12)
    private String movementType;

    /** Signed: {@code +10000.00} on a top-up, {@code -199.00} on a sale. */
    @Column(name = "FACE_DELTA", nullable = false, precision = 38, scale = 2)
    private BigDecimal faceDelta;

    @Column(name = "COST_DELTA", nullable = false, precision = 38, scale = 2)
    private BigDecimal costDelta;

    /**
     * Set on {@code TOPUP} only, and {@code ck_fmov_topup} enforces exactly that.
     * Null on weighted-average consumption, because a sale does not draw from an
     * identifiable batch.
     */
    @Column(name = "BATCH_ID")
    private Long batchId;

    @Column(name = "VOUCHER_ID", nullable = false)
    private Long voucherId;

    @Column(name = "RECHARGE_ID")
    private Integer rechargeId;

    /**
     * The ratio that WAS used, not redundancy: it lets a historical recharge's
     * cost be explained after the weighted average has moved on (§3.2, L8). Null
     * on a top-up, which sets the ratio rather than applying one.
     */
    @Column(name = "APPLIED_WA", precision = 18, scale = 8)
    private BigDecimal appliedWa;

    @Column(name = "APPLIED_RATE_ID")
    private Long appliedRateId;

    @Column(name = "MOVED_AT", nullable = false)
    private LocalDateTime movedAt;

    @Column(name = "NOTE", length = 255)
    private String note;

    @Column(name = "CRE_DTTM", insertable = false, updatable = false)
    private LocalDateTime creDttm;
}
