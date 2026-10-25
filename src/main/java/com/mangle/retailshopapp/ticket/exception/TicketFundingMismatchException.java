package com.mangle.retailshopapp.ticket.exception;

import java.util.Map;

import com.mangle.retailshopapp.recharge.exception.ErrorCode;
import com.mangle.retailshopapp.recharge.exception.RechargeRuleViolationException;

/**
 * 422 — a fare with no funding account, or a funding account with no fare.
 *
 * <p>{@code ck_tkt_funding} restated at the edge, so the caller gets a sentence
 * instead of a constraint-violation stack trace (design-ticket-booking.md §4).
 * The constraint is still the authority — this is not a substitute for it, it is
 * the same rule said earlier and in English.
 */
public class TicketFundingMismatchException extends RechargeRuleViolationException {

    public TicketFundingMismatchException(boolean hasFare) {
        super(ErrorCode.TICKET_FUNDING_MISMATCH,
                hasFare
                        ? "A fare needs the account that paid it: choose a funding account, "
                                + "or record this as the customer having paid the platform directly."
                        : "A funding account means the shop paid the fare, so a fare amount is "
                                + "required. Leave both empty if the customer paid directly.",
                Map.of("fareAmountPresent", hasFare));
    }
}
