package com.mangle.retailshopapp.ticket.model.request;

import java.math.BigDecimal;
import java.util.List;

import com.mangle.retailshopapp.ticket.domain.BookingMode;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /tickets/bookings} (design-ticket-booking.md §4).
 *
 * <p>⚠ WHAT IS NOT ANNOTATED HERE IS DELIBERATE. {@code passengerCount < 1} and
 * {@code commissionAmount < 0} carry no {@code @Min}, because §4 lists both among
 * the 422 conditions — "each one a rule from this document rather than a generic
 * validation". Annotating them too would mean two layers refusing the same thing
 * and whichever fired first deciding the status code. Presence is checked here;
 * the rules are checked in the service.
 *
 * <p>⚠ No total, no change and no flow. All three are server-owned or derived:
 * the total is {@code fareAmount + commissionAmount}, the change comes from the
 * tender, and the flow is read from whether a fare is present. A client that
 * sent any of them would be sending an opinion about arithmetic it does not own
 * (design-api.md §3.3).
 */
public record CreateBookingRequest(

        @NotNull(message = "a booking mode is required")
        BookingMode bookingMode,

        @NotNull(message = "a passenger count is required")
        Integer passengerCount,

        /**
         * Omitted or null for flow 2 — the customer paid the platform
         * themselves, so the shop never touched the fare.
         */
        BigDecimal fareAmount,

        @NotNull(message = "a commission amount is required; send 0 for none")
        BigDecimal commissionAmount,

        /** Required exactly when {@code fareAmount} is present (ck_tkt_funding). */
        String fundingAccountCode,

        /**
         * The same shape as design-api.md §3.3's, down to the validation: N rows
         * summing exactly to {@code fareAmount + commissionAmount}, at most one
         * {@code CREDIT}, {@code customerId} required on it.
         */
        @NotEmpty(message = "at least one settlement line is required")
        @Valid
        List<SettlementLineRequest> settlementLines,

        @Size(max = 255, message = "a note is at most 255 characters")
        String note) {
}
