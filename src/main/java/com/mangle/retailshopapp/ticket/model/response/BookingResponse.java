package com.mangle.retailshopapp.ticket.model.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.mangle.retailshopapp.ticket.domain.BookingMode;

/**
 * One booking as the wire sees it (design-ticket-booking.md §4).
 *
 * <p>{@code shopFunded} is sent even though it is derivable from
 * {@code fareAmount}, because the screen reads it as a state and should not have
 * to re-derive the flow — the server is the one authority for which flow a row
 * is, exactly as it is for the total.
 */
public record BookingResponse(
        long bookingId,
        LocalDateTime bookingDttm,
        BookingMode bookingMode,
        int passengerCount,
        BigDecimal fareAmount,
        BigDecimal commissionAmount,
        String fundingAccountCode,
        boolean shopFunded,
        BigDecimal totalDue,
        Integer customerId,
        String note,
        List<SettlementLineResponse> settlementLines,
        /** The voucher this booking posted. Null only if read before posting. */
        Long voucherId) {
}
