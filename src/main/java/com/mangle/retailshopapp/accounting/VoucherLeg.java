package com.mangle.retailshopapp.accounting;

import java.math.BigDecimal;

/**
 * One leg of a voucher to be posted.
 *
 * <p>Exactly one of {@code dr} / {@code cr} carries a positive amount and the
 * other is zero — the same shape {@code ck_line_oneside} and
 * {@code ck_line_nonzero} enforce in the database. The factory methods are the
 * intended way to build one, because they make the side explicit at the call
 * site: {@code VoucherLeg.debit("CASH_RECHARGE", amount)} reads as the posting
 * rule it implements.
 */
public record VoucherLeg(
        String accountCode,
        BigDecimal dr,
        BigDecimal cr,
        Integer partyCustId,
        String paymentMethod,
        String lineNarration) {

    public static VoucherLeg debit(String accountCode, BigDecimal amount) {
        return new VoucherLeg(accountCode, amount, BigDecimal.ZERO, null, null, null);
    }

    public static VoucherLeg credit(String accountCode, BigDecimal amount) {
        return new VoucherLeg(accountCode, BigDecimal.ZERO, amount, null, null, null);
    }

    public VoucherLeg withParty(Integer partyCustId) {
        return new VoucherLeg(accountCode, dr, cr, partyCustId, paymentMethod, lineNarration);
    }

    public VoucherLeg withPaymentMethod(String paymentMethod) {
        return new VoucherLeg(accountCode, dr, cr, partyCustId, paymentMethod, lineNarration);
    }

    public VoucherLeg withNarration(String lineNarration) {
        return new VoucherLeg(accountCode, dr, cr, partyCustId, paymentMethod, lineNarration);
    }

    BigDecimal drOrZero() {
        return dr == null ? BigDecimal.ZERO : dr;
    }

    BigDecimal crOrZero() {
        return cr == null ? BigDecimal.ZERO : cr;
    }
}
