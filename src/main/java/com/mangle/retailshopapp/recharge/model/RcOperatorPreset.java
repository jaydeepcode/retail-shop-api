package com.mangle.retailshopapp.recharge.model;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One amount chip for one operator (V10, replaced by V28; log §14.10).
 *
 * <p>A child table rather than a JSON column because requirement 10 says this
 * config is edited in tables with no screen — i.e. by hand in SQL — and ordered
 * scalars are far easier to edit as rows than inside a JSON document.
 *
 * <p>⚠ V28 REPLACED V10's SEEDS WITH THE MEASURED SETS, and the change is
 * structural rather than numeric: presets are PER OPERATOR, and TV gets none at
 * all. Dish TV's commonest amount is 16% of its volume against mobile's 78%, so
 * there is no short list to offer and the screen uses a ±₹10 stepper. An empty
 * list for a TV operator is therefore the correct answer, not missing data.
 */
@Getter
@Setter
@Entity
@Table(name = "rc_operator_preset")
public class RcOperatorPreset {

    @EmbeddedId
    private RcOperatorPresetId id;

    /** Commonest first, so the leftmost chip is the one most often wanted. */
    @Column(name = "SORT_ORDER", nullable = false)
    private int sortOrder;

    @Column(name = "IS_ACTIVE", nullable = false)
    private boolean active = true;

    public BigDecimal amount() {
        return id.getAmount();
    }

    public String operatorCode() {
        return id.getOperatorCode();
    }
}
