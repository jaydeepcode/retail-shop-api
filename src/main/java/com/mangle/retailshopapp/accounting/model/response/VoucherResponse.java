package com.mangle.retailshopapp.accounting.model.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.mangle.retailshopapp.accounting.model.AccVoucher;
import com.mangle.retailshopapp.accounting.model.AccVoucherLine;

/**
 * One voucher with its legs — the body of endpoints 7, 8, 9 and 19, and the shape
 * endpoints 10 and 11 will return when they arrive (§1.3).
 *
 * <p>Built from {@code acc_voucher} plus {@code acc_voucher_line} rather than
 * from the request, so a replay answers with what is actually on file. That
 * distinction cost S4 a defect: a replayed booking came back with no payment and
 * no voucher on it because the response echoed a request the second call never
 * made.
 *
 * <p>⚠ <b>{@code sealedAt} IS ON HERE ON PURPOSE.</b> Every report filters on
 * {@code SEALED_AT IS NOT NULL} and V23's trigger 1 asserts
 * {@code SUM(DR) = SUM(CR)} at the moment it is set, so a non-null value is the
 * client's evidence that the voucher is balanced, immutable and visible to the
 * books. A voucher interrupted between steps is invisible rather than wrong, and
 * a null here would say so.
 *
 * @param replayed true when this voucher already existed under the same
 *                 {@code Idempotency-Key} and nothing was written. The 409's
 *                 body carries it (§5.4): an automatic posting treats a replay as
 *                 success, while a user-initiated one reports it so the person
 *                 learns it was already done
 */
public record VoucherResponse(
        long voucherId,
        String voucherType,
        LocalDate voucherDate,
        LocalDateTime voucherDttm,
        String narration,
        String sourceType,
        String sourceRef,
        Long reversesVoucherId,
        LocalDateTime sealedAt,
        List<Leg> legs,
        boolean replayed) {

    /**
     * @param dr one of these two is positive and the other is {@code 0.00},
     *           never both and never neither ({@code ck_line_oneside},
     *           {@code ck_line_nonzero})
     */
    public record Leg(
            short lineNo,
            String accountCode,
            BigDecimal dr,
            BigDecimal cr,
            Integer partyCustId,
            String paymentMethod,
            String lineNarration) {

        public static Leg from(AccVoucherLine line) {
            return new Leg(
                    line.getLineNo(),
                    line.getAccountCode(),
                    paise(line.getDrAmount()),
                    paise(line.getCrAmount()),
                    line.getPartyCustId(),
                    line.getPaymentMethod(),
                    line.getLineNarration());
        }

        /**
         * ⚠ <b>NORMALISED TO SCALE 2, AND THIS WAS A DEFECT FOUND BY RUNNING IT
         * — the same one the day close found by another route.</b>
         *
         * <p>{@code VoucherLeg.debit} puts {@code BigDecimal.ZERO} on the unused
         * side, which is scale ZERO, and Hibernate does not read a column back
         * after inserting it. So a freshly posted voucher answered
         * {@code "cr": 0} on its debit legs while the same voucher RE-READ — a
         * replay, or endpoint 10 later — answered {@code "cr": 0.00} from
         * {@code decimal(38,2)}. Two shapes for one field, decided by whether the
         * caller happened to be the one that posted it.
         *
         * <p>{@code LedgerCloseService.paise} records the identical trap on
         * {@code COALESCE(SUM(...), 0)}, and {@code VARIANCE_AMOUNT} needed
         * {@code @Generated} for the same family of reason. Scale is part of the
         * wire contract whether or not anyone wrote it down.
         */
        private static BigDecimal paise(BigDecimal amount) {
            return amount == null ? new BigDecimal("0.00")
                    : amount.setScale(2, java.math.RoundingMode.UNNECESSARY);
        }
    }

    public static VoucherResponse from(AccVoucher voucher, List<AccVoucherLine> lines,
                                       boolean replayed) {
        return new VoucherResponse(
                voucher.getVoucherId(),
                voucher.getVoucherType(),
                voucher.getVoucherDate(),
                voucher.getVoucherDttm(),
                voucher.getNarration(),
                voucher.getSourceType(),
                voucher.getSourceRef(),
                voucher.getReversesVoucherId(),
                voucher.getSealedAt(),
                lines.stream().map(Leg::from).toList(),
                replayed);
    }
}
