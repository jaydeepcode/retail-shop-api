package com.mangle.retailshopapp.recharge.domain;

/**
 * What is being recharged — {@code ref_recharge_kind}'s two codes.
 *
 * <p>Named {@code ServiceType} on the wire and {@code RECHARGE_KIND} in the
 * schema. The mismatch is design-api.md §3.3's, kept rather than reconciled: the
 * column name predates the API and renaming either would break the other.
 */
public enum ServiceType {

    MOBILE,
    TV;

    /** The {@code rc_recharge.RECHARGE_KIND} value. Identical today, by design. */
    public String kindCode() {
        return name();
    }

    /**
     * Whether the shop's own surcharge defaults ON for this service type.
     *
     * <p>⚠ DTH ONLY, which NARROWS what §10.2 and §14.3 recorded, deliberately
     * (log §15.11). §10.2 concluded the surcharge was "not strictly DTH — 198
     * headers carry it on a mobile-only basket" and quoted the owner as "on
     * Recharge or any DTH associated recharge". Going forward it defaults to DTH
     * only. The 198 mobile cases stay as history and are not a defect, and
     * because the field stays editable a mobile line can still carry one if staff
     * type it — so nothing is made impossible, only the default changes.
     */
    public boolean surchargeOnByDefault() {
        return this == TV;
    }
}
