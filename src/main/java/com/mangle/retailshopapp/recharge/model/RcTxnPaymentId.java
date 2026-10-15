package com.mangle.retailshopapp.recharge.model;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * {@code rc_txn_payment}'s composite key.
 *
 * <p>{@code (TXN_ID, SEQ_NO)} mirrors {@code rc_txn_details}' key on purpose
 * (V12): the item lines and the payment lines of a basket then read the same way.
 *
 * <p>Hand-written {@code equals}/{@code hashCode} rather than Lombok, because an
 * {@code @Embeddable} id is used as a map key inside Hibernate and a generated
 * implementation that someone later changes the field order of would silently
 * change identity.
 */
@Embeddable
public class RcTxnPaymentId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "TXN_ID", nullable = false)
    private Integer txnId;

    @Column(name = "SEQ_NO", nullable = false)
    private Short seqNo;

    protected RcTxnPaymentId() {
        // Hibernate.
    }

    public RcTxnPaymentId(Integer txnId, Short seqNo) {
        this.txnId = txnId;
        this.seqNo = seqNo;
    }

    public Integer getTxnId() {
        return txnId;
    }

    public Short getSeqNo() {
        return seqNo;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RcTxnPaymentId other)) {
            return false;
        }
        return Objects.equals(txnId, other.txnId) && Objects.equals(seqNo, other.seqNo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(txnId, seqNo);
    }

    @Override
    public String toString() {
        return txnId + "#" + seqNo;
    }
}
