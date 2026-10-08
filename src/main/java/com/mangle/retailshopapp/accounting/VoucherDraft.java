package com.mangle.retailshopapp.accounting;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Everything needed to post one voucher.
 *
 * <p>{@code idempotencyKey} may be null for a hand-entered voucher, which has no
 * natural key. Every AUTOMATIC posting must set it, in the
 * {@code <SOURCE_TYPE>:<table>#<id>:<STAGE>} format §5.4 lays out — the
 * {@code :STAGE} suffix is what makes delete-and-repost and a correcting journal
 * able to coexist.
 */
public record VoucherDraft(
        String voucherType,
        LocalDate voucherDate,
        LocalDateTime voucherDttm,
        String narration,
        String sourceType,
        String sourceRef,
        String idempotencyKey,
        Long reversesVoucherId,
        Integer creBy,
        List<VoucherLeg> legs) {
}
