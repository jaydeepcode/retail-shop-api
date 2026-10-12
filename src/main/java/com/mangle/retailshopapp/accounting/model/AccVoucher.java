package com.mangle.retailshopapp.accounting.model;

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
 * A voucher header (V22, design-ledger.md §2.3).
 *
 * <p>Its legs are NOT mapped as a {@code @OneToMany}. That is deliberate: a
 * cascade would let Hibernate decide when the lines are written relative to the
 * seal, and the three-step order is exactly what V23's trigger 1 depends on —
 * insert the header unsealed, insert the legs, then set {@code SEALED_AT} so the
 * trigger can sum the children. {@code LedgerPostingService} issues those three
 * steps explicitly with a flush between them.
 *
 * <p>Once {@code SEALED_AT} is set the row is immutable: V23's trigger 1 refuses
 * every subsequent UPDATE, including un-sealing. A correction is a new JOURNAL
 * carrying {@code reversesVoucherId}, never an edit (FRD §8.3).
 */
@Getter
@Setter
@Entity
@Table(name = "acc_voucher")
public class AccVoucher {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "VOUCHER_ID")
    private Long voucherId;

    @Column(name = "VOUCHER_TYPE", nullable = false, length = 10)
    private String voucherType;

    /** The ACCOUNTING date: reports, reconciliation and §8.3 dating use this. */
    @Column(name = "VOUCHER_DATE", nullable = false)
    private LocalDate voucherDate;

    /** When the event happened; orders entries within a day. */
    @Column(name = "VOUCHER_DTTM", nullable = false)
    private LocalDateTime voucherDttm;

    @Column(name = "NARRATION", length = 255)
    private String narration;

    @Column(name = "SOURCE_TYPE", nullable = false, length = 20)
    private String sourceType;

    /** {@code table#id}, e.g. {@code rc_txn_header#73460}. A varchar, not an FK (§2.3). */
    @Column(name = "SOURCE_REF", length = 64)
    private String sourceRef;

    /** Nullable-unique. The whole duplicate-post guard (§5.4). */
    @Column(name = "IDEMPOTENCY_KEY", length = 120)
    private String idempotencyKey;

    @Column(name = "REVERSES_VOUCHER_ID")
    private Long reversesVoucherId;

    @Column(name = "SEALED_AT")
    private LocalDateTime sealedAt;

    @Column(name = "CRE_BY")
    private Integer creBy;
}
