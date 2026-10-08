package com.mangle.retailshopapp.accounting;

import java.time.LocalDateTime;

/**
 * The result of a post.
 *
 * <p>{@code replayed} is true when the voucher already existed under the same
 * idempotency key and nothing was written. Callers care about the difference:
 * an automatic posting treats a replay as success, while a user-initiated action
 * reports it (HTTP 409) so the user learns it was already done (§5.4).
 */
public record PostedVoucher(long voucherId, LocalDateTime sealedAt, int legCount, boolean replayed) {
}
