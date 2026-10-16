package com.mangle.retailshopapp.recharge.exception;

import java.util.Map;

/**
 * 409 — this failure already has a retry.
 *
 * <p>⚠ RAISED FROM THE DATABASE'S ANSWER, not from a prior read. §7's threat 3
 * chose a unique key on {@code FAILED_RECHARGE_ID} over an application check
 * precisely so that two terminals retrying one failure cannot both pass a
 * {@code SELECT} and both insert — "cheaper in the DB than in the app … no key,
 * no application-level lock". V29 adds the key V12 left non-unique.
 *
 * <p>A 409 rather than a 422 because it fits §8's distinction exactly: the
 * caller should look again. The failure HAS been dealt with — by someone else, a
 * moment ago — and refreshing shows the retry that already exists.
 */
public class DuplicateRetryException extends RechargeConflictException {

    public DuplicateRetryException(long failedRechargeId) {
        super(ErrorCode.DUPLICATE_RETRY,
                "Recharge %d has already been retried.".formatted(failedRechargeId),
                Map.of("rechargeId", failedRechargeId));
    }
}
