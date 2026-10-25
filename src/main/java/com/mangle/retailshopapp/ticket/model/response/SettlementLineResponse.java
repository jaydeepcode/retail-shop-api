package com.mangle.retailshopapp.ticket.model.response;

import java.math.BigDecimal;

import com.mangle.retailshopapp.settlement.SettlementMethod;

/**
 * One tender as it was recorded.
 *
 * <p>{@code changeDisplayed} is the SERVER's flag and never re-derived by a
 * client from {@code changeGiven} and a threshold — log §14.5 keeps change under
 * ₹5 off the screen, and the rule lives server-side so two clients cannot
 * disagree about it.
 */
public record SettlementLineResponse(
        SettlementMethod method,
        BigDecimal amount,
        BigDecimal amountTendered,
        BigDecimal changeGiven,
        /**
         * ⚠ {@code Boolean}, so NULL can mean "this was read back from the
         * voucher and the tender is not recorded there". False would be a claim
         * that no change was displayed, which on a replay would contradict what
         * the original 201 said.
         */
        Boolean changeDisplayed,
        String reference,
        Integer customerId,
        String note) {
}
