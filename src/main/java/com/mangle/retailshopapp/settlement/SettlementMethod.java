package com.mangle.retailshopapp.settlement;

/**
 * How a basket was paid for — {@code ref_payment_line_type}'s three codes.
 *
 * <p>⚠ THE WIRE NAME AND THE COLUMN VALUE DIFFER FOR CREDIT: the API says
 * {@code CREDIT} (design-api.md §3.3) and {@code rc_txn_payment.LINE_TYPE} holds
 * {@code CRDT}, which V9 seeds and 2,844 historical rows already use. Mapped here
 * rather than renamed at either end, because the column value is shared with
 * history and the wire name is shared with a screen.
 */
public enum SettlementMethod {

    CASH("CASH"),
    UPI("UPI"),
    CREDIT("CRDT");

    private final String lineType;

    SettlementMethod(String lineType) {
        this.lineType = lineType;
    }

    /** The {@code rc_txn_payment.LINE_TYPE} value. */
    public String lineType() {
        return lineType;
    }

    public static SettlementMethod fromLineType(String lineType) {
        for (SettlementMethod m : values()) {
            if (m.lineType.equals(lineType)) {
                return m;
            }
        }
        throw new IllegalArgumentException("not a settlement line type: " + lineType);
    }

    /** Only cash has a tender and change (§3.3). */
    public boolean carriesTender() {
        return this == CASH;
    }
}
