package com.mangle.retailshopapp.recharge.model;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * How a recharge reaches the operator (V9, design-domain.md §3.1).
 *
 * <p>Three codes: {@code DIRECT}, {@code A1TOPUP} and {@code SARAVATE}. The route
 * is half of what resolves the float wallet at runtime (log §15.1):
 *
 * <pre>
 * route = 'A1TOPUP'  -&gt;  FLOAT_A1TOPUP
 * route = 'DIRECT'   -&gt;  CONCAT('FLOAT_', OPERATOR_CODE)
 * </pre>
 *
 * <p>{@code SARAVATE} is seeded ACTIVE even though the vendor is retired, because
 * V16 is what retires it — and V15 needs it to exist first, since every
 * {@code SRVTE*} row dated before 2026-04-01 maps onto it. The split is at the
 * financial-year boundary because those codes carried on as a stand-in for A1Topup
 * after the vendor relationship ended (log §8.5).
 */
@Getter
@Setter
@Entity
@Table(name = "ref_recharge_route")
public class RefRechargeRoute {

    @Id
    @Column(name = "CODE", nullable = false, length = 20)
    private String code;

    @Column(name = "DESCRIPTION", nullable = false, length = 100)
    private String description;

    @Column(name = "IS_ACTIVE", nullable = false)
    private boolean active = true;

    @Column(name = "SORT_ORDER", nullable = false)
    private int sortOrder;

    @Column(name = "RETIRED_ON")
    private LocalDate retiredOn;

    /**
     * The float account this route consumes, given an operator (log §15.1).
     *
     * <p>Derived rather than looked up: §2.4 anticipated a mapping table and the
     * owner confirmed there is none, because the account is computable from the
     * two inputs the counter already captures. The ten-account form the design
     * previously generated was wrong in both directions — six accounts for float
     * the shop does not hold, and no account at all for A1Topup, the largest one
     * it does.
     */
    public String floatAccountFor(String operatorCode) {
        return "A1TOPUP".equals(code) ? "FLOAT_A1TOPUP" : "FLOAT_" + operatorCode;
    }
}
