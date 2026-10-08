package com.mangle.retailshopapp.accounting.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The chart of accounts (V21, design-ledger.md §2.2).
 *
 * <p>Read-only as far as this slice is concerned: the rows are seeded by the
 * migration and S2 never writes one. {@code LedgerPostingService} reads it to
 * check that a leg's account exists, is active, and — where
 * {@code REQUIRES_PARTY} is set — that the leg names a customer.
 *
 * <p>The primary key is the natural {@code ACCOUNT_CODE} (L1), not a surrogate
 * int. Thirty-odd rows that never change identity, and it makes the posting
 * rules a set of String constants a reviewer can check against FRD §5 by eye.
 */
@Getter
@Setter
@Entity
@Table(name = "acc_account")
public class AccAccount {

    @Id
    @Column(name = "ACCOUNT_CODE", length = 30)
    private String accountCode;

    @Column(name = "DISPLAY_NAME", nullable = false, length = 80)
    private String displayName;

    @Column(name = "ACCOUNT_TYPE", nullable = false, length = 12)
    private String accountType;

    @Column(name = "BUSINESS_CODE", nullable = false, length = 10)
    private String businessCode;

    @Column(name = "REPORT_GROUP", length = 30)
    private String reportGroup;

    /**
     * VARCHAR(20), matching rc_operator after S1's V10 widened it. NULL on
     * FLOAT_A1TOPUP, which answers to six operators (log §2.4).
     */
    @Column(name = "OPERATOR_CODE", length = 20)
    private String operatorCode;

    /** Non-null is what makes this a float account; see V21's header. */
    @Column(name = "FLOAT_PATTERN", length = 12)
    private String floatPattern;

    @Column(name = "IS_RECONCILABLE", nullable = false)
    private boolean reconcilable;

    /**
     * FRD §6's picker list. A7: FALSE keeps an account out of the GENERIC
     * voucher endpoints, not out of the ledger — so this is deliberately NOT
     * checked by the posting service. Automatic postings legitimately move
     * UPI_CLEARING, RCHG_PENDING and the float accounts.
     */
    @Column(name = "ALLOW_MANUAL", nullable = false)
    private boolean allowManual;

    /** TRUE for AR_* and ADV_*: a leg must name a customer. */
    @Column(name = "REQUIRES_PARTY", nullable = false)
    private boolean requiresParty;

    @Column(name = "CASH_POOL", length = 10)
    private String cashPool;

    @Column(name = "IS_POOL_PRIMARY", nullable = false)
    private boolean poolPrimary;

    @Column(name = "IS_ACTIVE", nullable = false)
    private boolean active;

    @Column(name = "SORT_ORDER", nullable = false)
    private int sortOrder;

    @Column(name = "NOTE", length = 255)
    private String note;
}
