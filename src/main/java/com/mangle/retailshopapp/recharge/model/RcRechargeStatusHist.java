package com.mangle.retailshopapp.recharge.model;

import java.time.LocalDateTime;

import com.mangle.retailshopapp.recharge.domain.RechargeStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The status audit trail (V12, design-domain.md §5.4).
 *
 * <p>Appended inside the same transaction as the status write. §5.4 calls it
 * "recommended rather than strictly required by v1: it costs one small table and
 * answers 'when did it fail', which the client will ask within a week."
 *
 * <p>It is NOT a substitute for the {@code audit} package, which is about user
 * actions rather than domain state — and §4.4 is explicit that this module does
 * not use {@code @Auditable} at all, because {@code AuditAspect} is an
 * {@code @Around} advice whose ordering would decide whether the audit write
 * joins the business transaction.
 *
 * <p>{@code FROM_STATUS} is nullable for the creating transition, which has no
 * prior state. {@code REASON} is nullable in the DATABASE because it cannot tell
 * which transitions mandate one — {@code COMPLETED → VOIDED} does (log §7.1) —
 * and the state machine enforces that instead.
 */
@Getter
@Setter
@Entity
@Table(name = "rc_recharge_status_hist")
public class RcRechargeStatusHist {

    /** {@code Integer}: V12 declares {@code HIST_ID INT}. See {@link RcRecharge}. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "HIST_ID")
    private Integer histId;

    @Column(name = "RECHARGE_ID", nullable = false)
    private Integer rechargeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "FROM_STATUS", length = 20)
    private RechargeStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "TO_STATUS", nullable = false, length = 20)
    private RechargeStatus toStatus;

    @Column(name = "CHANGED_AT", nullable = false)
    private LocalDateTime changedAt;

    @Column(name = "CHANGED_BY")
    private Integer changedBy;

    @Column(name = "REASON", length = 255)
    private String reason;

    public static RcRechargeStatusHist of(Integer rechargeId, RechargeStatus from, RechargeStatus to,
                                          Integer changedBy, String reason, LocalDateTime when) {
        RcRechargeStatusHist h = new RcRechargeStatusHist();
        h.rechargeId = rechargeId;
        h.fromStatus = from;
        h.toStatus = to;
        h.changedBy = changedBy;
        h.reason = reason;
        h.changedAt = when;
        return h;
    }
}
