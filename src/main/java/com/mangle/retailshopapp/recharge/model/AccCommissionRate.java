package com.mangle.retailshopapp.recharge.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * An effective-dated commission rate (V25, design-ledger.md §4).
 *
 * <p>Mapped in the recharge module rather than in {@code accounting} because the
 * recharge completion is its only reader in v1, and {@code accounting} ships no
 * rate-admin path until S5. If a second business ever needs rates, this moves.
 *
 * <p>⚠ {@code RATE_PCT} IS A PERCENTAGE, NOT A FRACTION: {@code 2.900000} means
 * 2.9%. {@link #rateAsFraction()} is the only thing that should convert it, and
 * {@code FloatConsumption} takes the fraction — getting that wrong by a factor of
 * 100 would make a ₹199 recharge consume ₹141 of float and still balance, which
 * is exactly the kind of error that balances and is wrong.
 *
 * <p><b>A rate is never edited.</b> §4.1: it is closed and superseded instead,
 * because an edit would move historical P&L — the rate that applied on the day a
 * recharge happened is what that recharge earned. {@code LedgerCorrectionRules}
 * holds that rule as accounting policy rather than as a trigger, so it returns a
 * sentence instead of {@code SIGNAL SQLSTATE '45000'}.
 */
@Getter
@Setter
@Entity
@Table(name = "acc_commission_rate")
public class AccCommissionRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "RATE_ID")
    private Long rateId;

    @Column(name = "OPERATOR_CODE", nullable = false, length = 20)
    private String operatorCode;

    /** {@code '*'} means any route. No seeded row uses it (V25). */
    @Column(name = "ROUTE_CODE", nullable = false, length = 20)
    private String routeCode = "*";

    @Column(name = "PLAN_TIER", nullable = false, length = 30)
    private String planTier = "*";

    @Column(name = "MIN_AMOUNT", nullable = false, precision = 38, scale = 2)
    private BigDecimal minAmount = new BigDecimal("0.00");

    /** Null means unbounded above. The band is half-open, {@code [MIN, MAX)}. */
    @Column(name = "MAX_AMOUNT", precision = 38, scale = 2)
    private BigDecimal maxAmount;

    @Column(name = "RATE_PCT", nullable = false, precision = 9, scale = 6)
    private BigDecimal ratePct;

    @Column(name = "EFFECTIVE_FROM", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "EFFECTIVE_TO")
    private LocalDate effectiveTo;

    @Column(name = "NOTE", length = 255)
    private String note;

    @Column(name = "SUPERSEDED_BY")
    private Long supersededBy;

    @Column(name = "CRE_BY")
    private Integer creBy;

    /**
     * The rate as the fraction {@code r} that {@code FloatConsumption} expects.
     *
     * <p>Eight decimal places so that a rate like 3.333333% does not lose
     * precision before the face value is rounded — the rounding belongs at the
     * money, not at the rate.
     */
    public BigDecimal rateAsFraction() {
        return ratePct.divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP);
    }
}
