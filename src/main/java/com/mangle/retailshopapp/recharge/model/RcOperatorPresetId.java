package com.mangle.retailshopapp.recharge.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * {@code rc_operator_preset}'s composite key, {@code (OPERATOR_CODE, AMOUNT)}.
 *
 * <p>The amount is part of the key rather than a surrogate id because the row IS
 * the fact "this operator offers this amount" — two rows for ₹299 would be
 * meaningless, and requirement 10 has this table edited by hand in SQL, where a
 * natural key is far easier to get right.
 */
@Embeddable
public class RcOperatorPresetId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "OPERATOR_CODE", nullable = false, length = 20)
    private String operatorCode;

    @Column(name = "AMOUNT", nullable = false, precision = 38, scale = 2)
    private BigDecimal amount;

    protected RcOperatorPresetId() {
        // Hibernate.
    }

    public RcOperatorPresetId(String operatorCode, BigDecimal amount) {
        this.operatorCode = operatorCode;
        this.amount = amount;
    }

    public String getOperatorCode() {
        return operatorCode;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RcOperatorPresetId other)) {
            return false;
        }
        // compareTo, not equals: BigDecimal.equals treats 299.0 and 299.00 as
        // different, and the same amount read back from MySQL at a different
        // scale would then be a different key.
        return Objects.equals(operatorCode, other.operatorCode)
                && amount != null && other.amount != null
                && amount.compareTo(other.amount) == 0;
    }

    @Override
    public int hashCode() {
        // Must agree with equals, so it cannot include the scale.
        return Objects.hash(operatorCode,
                amount == null ? null : amount.stripTrailingZeros().toPlainString());
    }
}
