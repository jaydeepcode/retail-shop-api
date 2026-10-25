package com.mangle.retailshopapp.ticket.domain;

/**
 * Rail or bus — {@code ref_booking_mode}'s two codes (V31).
 *
 * <p>⚠ ONE BUSINESS WITH A MODE DIMENSION, NOT TWO BUSINESSES. Rail and bus have
 * identical economics — ₹60 per person either way, nothing prepaid to the
 * platform — so they share one income account, {@code INC_TKT_COMM} (T3, log
 * §10.7), and FRD §2.1 already treats them as one. A second income account for
 * bus would make "what did ticketing earn" a sum over two accounts for no reason
 * anyone could state.
 *
 * <p>The mode is a reference table rather than an enum column with a
 * {@code CHECK} so the screen's picker is a query and a hypothetical third mode
 * is a data change rather than a release (design-ticket-booking.md §2). The enum
 * exists because the code needs names, and
 * {@code TicketBookingMigrationTest.enumMatchesReferenceTable} is what keeps the
 * two from drifting.
 */
public enum BookingMode {

    RAIL,
    BUS
}
