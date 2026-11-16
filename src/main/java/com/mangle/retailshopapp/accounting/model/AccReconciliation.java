package com.mangle.retailshopapp.accounting.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One account's count on one business day (V22, design-ledger.md §2.4).
 *
 * <p><b>One row per ACCOUNT, never one per pool</b> (A2, log §9.1, E17d). The
 * row is what arms the lock in
 * {@link com.mangle.retailshopapp.accounting.LedgerCorrectionRules#assertVoucherDeletable(long)}
 * — {@code RECON_DATE >= VOUCHER_DATE} is tested per {@code ACCOUNT_CODE}, so a
 * single pool-level row would leave {@code CASH_TICKET} editable after its
 * drawer had been counted.
 *
 * <p>⚠ {@code LEDGER_AMOUNT} IS THE DERIVED BALANCE AS IT STOOD BEFORE THE
 * VARIANCE JOURNAL, and that is why {@code LedgerCloseService} writes these rows
 * before it posts anything. V22:146-152 answers the obvious objection — FRD §8.4
 * forbids a MAINTAINED balance that reports read instead of the vouchers, and
 * this is a one-time observation. Once the variance journal exists the
 * pre-adjustment balance is no longer recoverable from the vouchers at all,
 * because they are append-only, so it is recorded here or the evidence of what
 * the drawer actually held is destroyed permanently.
 *
 * <p>No {@code @ManyToOne} to {@link AccAccount} or {@link AccVoucher}, matching
 * {@link AccVoucherLine}: the columns are plain, the database owns the three
 * foreign keys, and nothing here wants a lazy proxy on a read of thirty rows.
 */
@Getter
@Setter
@Entity
@Table(name = "acc_reconciliation")
public class AccReconciliation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "RECON_ID")
    private Long reconId;

    @Column(name = "ACCOUNT_CODE", nullable = false, length = 30)
    private String accountCode;

    /**
     * The BUSINESS day being sealed, chosen by the caller.
     *
     * <p>⚠ Not "today". The lock test is {@code RECON_DATE >= VOUCHER_DATE}, so a
     * close performed at 00:30 and dated today seals every one of yesterday's
     * vouchers while yesterday itself never receives a count — sealed, uncounted
     * and unamendable, with no row anywhere recording what was in the drawer
     * (design-ledger-api.md §1.1, "reconDate on #2 and #4 is chosen").
     */
    @Column(name = "RECON_DATE", nullable = false)
    private LocalDate reconDate;

    /** The independent source: a cash count, a bank statement, a portal balance. */
    @Column(name = "COUNTED_AMOUNT", nullable = false, precision = 38, scale = 2)
    private BigDecimal countedAmount;

    @Column(name = "LEDGER_AMOUNT", nullable = false, precision = 38, scale = 2)
    private BigDecimal ledgerAmount;

    /**
     * {@code COUNTED_AMOUNT - LEDGER_AMOUNT}, a generated STORED column.
     *
     * <p>Read-only on purpose — {@code insertable = false, updatable = false}.
     * The database derives it so it can never disagree with its two inputs, and
     * Hibernate must not try to write it.
     *
     * <p>⚠ {@code @Generated} IS WHAT MAKES IT READABLE AFTER AN INSERT, and
     * leaving it off is a quiet defect rather than a missed optimisation.
     * Without it Hibernate writes the row and never reads the column back, so
     * the in-memory entity keeps {@code null} — and because endpoints 3 and 4
     * answer straight out of this entity, <b>every close would report
     * {@code "varianceAmount": null}</b> while the database held the right
     * figure. It survives a round trip through the database, which is exactly
     * what makes it easy to miss.
     *
     * <p>The alternative was to subtract the two inputs again in the response
     * mapper. Rejected: the point of a generated column is that one place
     * derives it, and a second subtraction in Java is a second thing to
     * disagree. {@code UPDATE} is included because the close does update this
     * row — to set {@code VARIANCE_VOUCHER_ID} — and a stale read-back there
     * would be the same bug arriving later.
     */
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "VARIANCE_AMOUNT", precision = 38, scale = 2,
            insertable = false, updatable = false)
    private BigDecimal varianceAmount;

    /** Float accounts only: the portal's face balance (§3). */
    @Column(name = "FACE_COUNTED", precision = 38, scale = 2)
    private BigDecimal faceCounted;

    /** Float accounts only: {@code SUM(acc_float_movement.FACE_DELTA)} (§3). */
    @Column(name = "FACE_LEDGER", precision = 38, scale = 2)
    private BigDecimal faceLedger;

    /** FRD §8.1's "a variance with a note". */
    @Column(name = "NOTE", length = 255)
    private String note;

    /**
     * The voucher that booked the variance — deliberately not restricted to a
     * {@code JOURNAL}. Log §9.1 part 2 books a variance with a KNOWN cause as an
     * ordinary {@code PAYMENT} for the real expense, because booking it to "Cash
     * Short" throws away the one fact worth keeping.
     */
    @Column(name = "VARIANCE_VOUCHER_ID")
    private Long varianceVoucherId;

    /**
     * When the drawer was physically emptied, as against which business day was
     * sealed. The two agree on an ordinary evening and diverge whenever a close
     * happens after midnight, which is why §1.1's preview returns both.
     */
    @Column(name = "CLOSED_AT", nullable = false)
    private LocalDateTime closedAt;

    @Column(name = "CLOSED_BY")
    private Integer closedBy;
}
