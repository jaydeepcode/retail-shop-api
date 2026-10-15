package com.mangle.retailshopapp.recharge.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One tender on a basket (V12, design-domain.md §4.3).
 *
 * <p>⚠ {@code AMOUNT} IS ALWAYS POSITIVE AND THE LINES SUM TO
 * {@code txn_total_amt} — a sign flip against history, where the 2,844
 * {@code CRDT} rows in {@code rc_txn_details} are all negative and cancel the
 * total out rather than composing it. V13 is what flips them on cutover day; new
 * sales are written the new way from the start.
 *
 * <p>{@code EXTENDED_BY} and {@code NOTE} live here and not only on
 * {@code rc_credit_req}, because requirement 7's credit-line facts are facts
 * about THIS settlement. They are also the whole of the authorisation control:
 * §3.5 says requirement 9 means no per-row authorisation at all, so what makes
 * credit accountable is the record of who extended it, taken from
 * {@code Principal}.
 *
 * <p><b>⚠ There is deliberately no tendered/change pair here.</b> The settle
 * response carries {@code amountTendered} and {@code changeGiven} on the cash
 * LINE (§3.3), so a reader of the response reasonably expects columns that do not
 * exist. They are assembled from {@code rc_txn_header.amt_tndred} and
 * {@code amt_returned}, because the tender belongs to the BASKET rather than to
 * one line — there is one drawer and one hand-over, whatever the basket holds.
 */
@Getter
@Setter
@Entity
@Table(name = "rc_txn_payment")
public class RcTxnPayment {

    @EmbeddedId
    private RcTxnPaymentId id;

    /** {@code CASH}, {@code UPI} or {@code CRDT} — note the wire name is CREDIT. */
    @Column(name = "LINE_TYPE", nullable = false, length = 10)
    private String lineType;

    @Column(name = "AMOUNT", nullable = false, precision = 38, scale = 2)
    private BigDecimal amount;

    /** The UPI reference. Meaningless on a cash line. */
    @Column(name = "REFERENCE_NO", length = 255)
    private String referenceNo;

    /**
     * The credit-book row this line wrote.
     *
     * <p>{@code ck_txnpay_credit} enforces that a {@code CRDT} line always has
     * one: a credit line without the credit-book row it wrote is meaningless, and
     * unlike "credit requires a named customer" that rule lives inside a single
     * row, so a {@code CHECK} can hold it.
     */
    @Column(name = "CRE_REQ_ID")
    private Integer creReqId;

    @Column(name = "EXTENDED_BY")
    private Integer extendedBy;

    @Column(name = "NOTE", length = 255)
    private String note;

    @Column(name = "CRE_DTTM", nullable = false)
    private LocalDateTime creDttm;

}
