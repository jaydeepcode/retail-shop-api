package com.mangle.retailshopapp.recharge.model;

import java.time.LocalDate;

import com.mangle.retailshopapp.recharge.domain.ServiceType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A recharge operator (V10, design-domain.md §3.2).
 *
 * <p>{@code OPERATOR_CODE} is a NATURAL key and never changes by design, which is
 * why the float account can be derived from it at runtime with no mapping table
 * (log §15.1).
 *
 * <p>⚠ {@code IDEA} and {@code VDFN} ARE RETIRED, not deleted: {@code VI} is the
 * live code (V20, log §15.2). They stay because older {@code rc_recharge} rows
 * will foreign-key them — a retired operator still satisfies
 * {@code fk_rech_operator}, and {@code IS_ACTIVE} governs the picker rather than
 * referential integrity. The counter must offer Vi, not Idea and Vodafone.
 *
 * <p>The {@code SRVTE*} codes are NOT operators and never become any: a
 * {@code SRVTEA} row means "an Airtel recharge, put through Saravate", so it is a
 * route in disguise. V10 excludes them from this table and V15 splits them into
 * an operator plus a route.
 */
@Getter
@Setter
@Entity
@Table(name = "rc_operator")
public class RcOperator {

    @Id
    @Column(name = "OPERATOR_CODE", nullable = false, length = 20)
    private String operatorCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "RECHARGE_KIND", nullable = false, length = 10)
    private ServiceType rechargeKind;

    @Column(name = "LOOKUP_TYPE", nullable = false, length = 5)
    private String lookupType;

    @Column(name = "LOOKUP_CODE", nullable = false, length = 7)
    private String lookupCode;

    /**
     * What a human sees. Every operator in a response carries both this and its
     * code (§6.1), so the UI never has to know that {@code RJIO} means Jio.
     */
    @Column(name = "DISPLAY_NAME", nullable = false, length = 45)
    private String displayName;

    /**
     * Where this operator's recharges go by default.
     *
     * <p>⚠ PER OPERATOR, NEVER GLOBALLY {@code DIRECT} (log §14.6): BSNL plus all
     * five TV operators have no direct connection, which is 382 of 1,037 FY
     * recharges. A global {@code DIRECT} default would send every one of them
     * into §8.2's rate refusal, because no such rate row is seeded.
     *
     * <p>And config-driven, never computed from history: the TV default
     * contradicts the history 7,899:9 and must not be "corrected" (§5.3).
     */
    @Column(name = "DEFAULT_ROUTE_CODE", nullable = false, length = 20)
    private String defaultRouteCode;

    @Column(name = "IS_ACTIVE", nullable = false)
    private boolean active = true;

    @Column(name = "SORT_ORDER", nullable = false)
    private int sortOrder;

    @Column(name = "RETIRED_ON")
    private LocalDate retiredOn;

    /**
     * Whether this code may be offered for a NEW recharge.
     *
     * <p>Distinct from {@link #isActive()} only in intent, and worth a name
     * because the two sentinels ({@code MOBL_UNKNOWN}, {@code TVOPR_UNKNOWN})
     * exist solely so V15's backfill has somewhere to put 9,155 rows with no
     * usable operator. They are seeded inactive and must never reach the picker.
     */
    public boolean isSelectable() {
        return active && !operatorCode.endsWith("_UNKNOWN");
    }
}
