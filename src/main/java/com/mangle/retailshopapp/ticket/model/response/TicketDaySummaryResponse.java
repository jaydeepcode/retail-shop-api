package com.mangle.retailshopapp.ticket.model.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Endpoint #4 — what ticketing did in a day.
 *
 * <p>⚠ THE FIGURES ARE READABLE ON THEIR OWN, which next-session-s4.md asks for
 * explicitly: {@code CASH_TICKET} shares POOL-1 with {@code CASH_RECHARGE}, so
 * once bookings post, a cash difference has two businesses in one drawer to be
 * attributed between. A summary that only gave a pool total would leave the
 * owner unable to say which business the variance came from.
 *
 * <p>{@code byFundingAccount} is per-account and not a single total for the same
 * reason: four bank accounts fund tickets, and "₹8,400 left the banks today" does
 * not tell the owner whose account to check.
 */
public record TicketDaySummaryResponse(
        LocalDate date,
        int bookings,
        int passengers,
        /** What the shop laid out on fares. Flow-2 bookings contribute nothing. */
        BigDecimal fareTotal,
        /** The margin — this is what ticketing actually earned. */
        BigDecimal commissionTotal,
        /** Fares plus commission: what customers paid across the day. */
        BigDecimal collectedTotal,
        List<FundingAccountTotal> byFundingAccount) {

    /** One funding account's outlay for the day. */
    public record FundingAccountTotal(String accountCode, int bookings, BigDecimal fareTotal) {
    }
}
