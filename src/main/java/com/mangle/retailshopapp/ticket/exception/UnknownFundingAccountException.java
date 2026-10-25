package com.mangle.retailshopapp.ticket.exception;

import java.util.List;
import java.util.Map;

import com.mangle.retailshopapp.recharge.exception.ErrorCode;
import com.mangle.retailshopapp.recharge.exception.RechargeRuleViolationException;

/**
 * 422 — the funding account is not one of the four, or is not active.
 *
 * <p>The permitted set is a property of the chart of accounts, not a hardcoded
 * list: {@code BANK_JANATA}, {@code BANK_SELF}, {@code BANK_WIFE} and
 * {@code BANK_FATHER} are the four the owner names (log §10.6), and the check is
 * that the code is one of them AND {@code IS_ACTIVE}. Deactivating an account
 * should stop new bookings naming it, which a hardcoded list would not do.
 *
 * <p>The message names what IS allowed, because "BANK_HDFC is not valid" leaves
 * the staff member guessing at the spelling of the one they want.
 */
public class UnknownFundingAccountException extends RechargeRuleViolationException {

    public UnknownFundingAccountException(String given, List<String> permitted) {
        super(ErrorCode.UNKNOWN_FUNDING_ACCOUNT,
                "%s is not an account this shop funds tickets from. Choose one of: %s."
                        .formatted(given, String.join(", ", permitted)),
                Map.of("fundingAccountCode", given, "permitted", permitted));
    }
}
