package com.mangle.retailshopapp.recharge.exception;

/**
 * 409 — someone else changed this row first.
 *
 * <p>⚠ RETAINED THOUGH THE OPTIMISTIC-LOCK CONTRACT WAS DROPPED, and the reason
 * is that the race is real even without a version column. design-api.md §3.3
 * presents a {@code version} field on every recharge and a 409 "version
 * conflict"; {@code rc_recharge} has no such column, and design-domain.md §5.4
 * specifies {@code PESSIMISTIC_WRITE} instead — which log §8.1 also relies on for
 * the float check at completion. So the lock is pessimistic and this exception
 * reports the case the lock cannot absorb: a row whose status moved between the
 * read that showed it on the worklist and the write that tried to complete it.
 */
public class ConcurrentModificationException extends RechargeConflictException {

    public ConcurrentModificationException(String message) {
        super(ErrorCode.CONCURRENT_MODIFICATION, message);
    }
}
