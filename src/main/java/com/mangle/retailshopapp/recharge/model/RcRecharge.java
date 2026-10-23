package com.mangle.retailshopapp.recharge.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.mangle.retailshopapp.recharge.domain.RechargeStatus;
import com.mangle.retailshopapp.recharge.domain.ServiceType;

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
 * One recharge — the aggregate root (V12, design-domain.md §4.4).
 *
 * <p>{@code @Getter @Setter} rather than {@code @Data}, which is where
 * design-api.md §6.1 is stale: it says "entities stay Lombok {@code @Data} to
 * match {@code credit/model/RcTxnHeader.java:17}", but S0 changed that very class
 * away from {@code @Data} because a generated {@code toString} across a
 * bidirectional reference is a guaranteed {@code StackOverflowError} on the first
 * log of a transaction (implementation-plan.md §3). S2's {@code AccVoucher} uses
 * {@code @Getter @Setter} for the same reason, and this follows it.
 *
 * <p><b>No {@code @OneToMany} to the payment or the item line.</b> The basket is
 * reached through {@code TXN_ID} and the queries that need both join explicitly.
 * A collection here would make the worklist — which reads one recharge at a time
 * and never wants its basket — pull a payment and its lines per row.
 *
 * <p><b>No {@code @Version} column.</b> design-api.md §3.3 shows a
 * {@code version} field on every recharge and §3.2 lists a 409 "version
 * conflict", but {@code rc_recharge} has no such column and
 * design-domain.md §5.4 specifies {@code PESSIMISTIC_WRITE} instead — which log
 * §8.1 also relies on, having moved the float availability check and its
 * {@code SELECT ... FOR UPDATE} to the completion moment. One locking strategy,
 * and it is the one the rest of the design already assumes.
 */
@Getter
@Setter
@Entity
@Table(name = "rc_recharge")
public class RcRecharge {

    /**
     * ⚠ {@code Integer}, NOT {@code Long}, because {@code V12:74} declares
     * {@code RECHARGE_ID INT} and an entity must say what the schema says.
     *
     * <p>It was {@code Long} until 2026-10-22, and nothing caught it:
     * {@code ddl-auto=validate} runs in the {@code local} profile ALONE — default,
     * {@code container} and {@code prod} all use {@code none} — so the container
     * tests and every deployed profile skipped the check. It surfaced the first
     * time the application was started from an IDE against the developer restore,
     * and it had made {@code validate} unusable: the one mechanism that would
     * notice a renamed or missing column could not get past this.
     *
     * <p><b>The column is right and the field was wrong</b>, which is why this
     * narrowed rather than V12 widening. {@code INT} tops out at 2,147,483,647
     * against 73,469 payments taken since 2019 — roughly 12k a year. The ledger
     * tables use {@code BIGINT} deliberately ({@code acc_voucher.VOUCHER_ID},
     * V22:99) because a sale writes several voucher LINES, so they grow at a
     * different rate. The distinction is intentional, not an oversight to
     * normalise away.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "RECHARGE_ID")
    private Integer rechargeId;

    /**
     * {@code MOBILE} or {@code TV}. The column is {@code RECHARGE_KIND} and the
     * wire name is {@code serviceType} — design-api.md §3.3's mismatch, kept
     * because the column predates the API and renaming either breaks the other.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "RECHARGE_KIND", nullable = false, length = 10)
    private ServiceType rechargeKind;

    /** {@code VARCHAR(20)}, widened in V10 to hold {@code TVOPR_UNKNOWN}. */
    @Column(name = "OPERATOR_CODE", nullable = false, length = 20)
    private String operatorCode;

    /**
     * Defaulted at settlement from the operator's configured default, and
     * OVERRIDABLE at completion (log §14.6).
     *
     * <p>At the moment the customer pays, nobody yet knows which connection will
     * be used — staff find out when they open the operator app. And the route is
     * not needed before then: §7.2 does not touch the float until completion.
     * Measured frequency of an override on a direct-route operator in FY2026-27:
     * two recharges, both on 2026-08-20.
     */
    @Column(name = "ROUTE_CODE", nullable = false, length = 20)
    private String routeCode;

    /**
     * The mobile number or TV connection number, as typed.
     *
     * <p>NO foreign key, on purpose: most of these correspond to no row anywhere
     * — 9,776 distinct recharged mobile numbers against 506 customers.
     */
    @Column(name = "TARGET_NUMBER", nullable = false, length = 20)
    private String targetNumber;

    /** The optional real relation, set only when the number is a known connection. */
    @Column(name = "DISHTV_NO", length = 12)
    private String dishtvNo;

    /** The recharge face value. NEVER includes the surcharge. */
    @Column(name = "AMOUNT", nullable = false, precision = 38, scale = 2)
    private BigDecimal amount;

    /**
     * The shop's own charge on this recharge (V28; log §10.2, §15.11).
     *
     * <p>Its own amount, never folded into {@link #amount}: the two reach
     * different income accounts, and folding them would destroy the only check
     * there is on the operator-paid rate. Keeping it separate is what lets
     * operator commission stay exactly {@code A − round(A × (1 − r), 2)} and
     * remain checkable against the rate card and the portal debit.
     *
     * <p>Defaults to ₹10 on TV and zero on mobile, from
     * {@code rc_setting['surcharge.default.*']}, and stays editable — the owner
     * was explicit. So a mobile line can still carry one if staff type it.
     */
    @Column(name = "SURCHARGE", nullable = false, precision = 38, scale = 2)
    private BigDecimal surcharge = new BigDecimal("0.00");

    @Column(name = "RECHARGE_DTTM", nullable = false)
    private LocalDateTime rechargeDttm;

    @Enumerated(EnumType.STRING)
    @Column(name = "STATUS_CODE", nullable = false, length = 20)
    private RechargeStatus statusCode;

    /** The settling payment. {@code MEDIUMINT} in the schema. */
    @Column(name = "TXN_ID", nullable = false)
    private Integer txnId;

    /** Its item line in {@code rc_txn_details}. */
    @Column(name = "SEQ_NO", nullable = false)
    private Integer seqNo;

    /**
     * The attempt this retries — a self-reference.
     *
     * <p>The retry carries the SAME {@code txnId} as the original: no new
     * payment, no second item line. §3.3 calls that "the invariant requirement 5
     * is really asking for", and it is enforced in the service rather than the
     * controller.
     */
    @Column(name = "FAILED_RECHARGE_ID")
    private Integer failedRechargeId;

    /** The operator or vendor reference; v2's aggregator transaction id. */
    @Column(name = "EXTERNAL_REF", length = 64)
    private String externalRef;

    @Column(name = "FAILURE_REASON", length = 255)
    private String failureReason;

    /** The staff member who took the sale. NULL for every backfilled row. */
    @Column(name = "CRE_BY")
    private Integer creBy;

    @Column(name = "CRE_DTTM", nullable = false)
    private LocalDateTime creDttm;

    @Column(name = "STATUS_CHANGED_AT")
    private LocalDateTime statusChangedAt;

    @Column(name = "STATUS_CHANGED_BY")
    private Integer statusChangedBy;

    /** What the customer paid for this line. */
    public BigDecimal lineTotal() {
        return amount.add(surcharge);
    }
}
