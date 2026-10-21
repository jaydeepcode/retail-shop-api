package com.mangle.retailshopapp.recharge.domain;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.mangle.retailshopapp.recharge.domain.RechargeStatus.AWAITING_RECHARGE;
import static com.mangle.retailshopapp.recharge.domain.RechargeStatus.COMPLETED;
import static com.mangle.retailshopapp.recharge.domain.RechargeStatus.FAILED;
import static com.mangle.retailshopapp.recharge.domain.RechargeStatus.REFUNDED;
import static com.mangle.retailshopapp.recharge.domain.RechargeStatus.RETRIED;
import static com.mangle.retailshopapp.recharge.domain.RechargeStatus.VOIDED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RechargeStatus and the derived resolution")
class RechargeStatusTest {

    @Test
    @DisplayName("the legal transitions are exactly design-domain.md §5.4's table")
    void legalTransitions() {
        assertEquals(EnumSet.of(COMPLETED, FAILED), allowedFrom(AWAITING_RECHARGE));
        assertEquals(EnumSet.of(FAILED), allowedFrom(COMPLETED),
                "VOIDED was removed from this row on 2026-10-21");
        assertEquals(EnumSet.of(RETRIED, REFUNDED), allowedFrom(FAILED));
        assertEquals(EnumSet.noneOf(RechargeStatus.class), allowedFrom(VOIDED));
        assertEquals(EnumSet.noneOf(RechargeStatus.class), allowedFrom(RETRIED));
        assertEquals(EnumSet.noneOf(RechargeStatus.class), allowedFrom(REFUNDED));
    }

    @Test
    @DisplayName("COMPLETED is not terminal, and FAILED is its one exit")
    void completedIsNotTerminal() {
        // That COMPLETED is not terminal reverses what design-api.md §3.3 said
        // before 2026-09-29, and it still holds — but for ONE arrow now, not two.
        // §7.1 added VOIDED alongside FAILED; see nothingReachesVoided for why
        // that half was withdrawn on 2026-10-21.
        assertFalse(COMPLETED.isTerminal());
        assertTrue(COMPLETED.canTransitionTo(FAILED));

        assertTrue(VOIDED.isTerminal());
        assertTrue(RETRIED.isTerminal());
        assertTrue(REFUNDED.isTerminal());
    }

    @Test
    @DisplayName("FAILED is the open state, so it is not terminal")
    void failedIsOpen() {
        // §5.2: "unresolved failures" is STATUS_CODE='FAILED', with no extra
        // IS_RESOLVED flag — resolving moves it to a terminal status instead.
        assertFalse(FAILED.isTerminal());
        assertFalse(AWAITING_RECHARGE.isTerminal());
    }

    @Test
    @DisplayName("a move to the same status is not a transition")
    void sameStatusIsNotATransition() {
        // The service treats a repeat as an idempotent 200 no-op (§5.3), which is
        // a different thing from a legal transition. Keeping them apart here is
        // what lets the state machine stay ignorant of HTTP.
        for (RechargeStatus s : RechargeStatus.values()) {
            assertFalse(s.canTransitionTo(s), s + " should not transition to itself");
        }
    }

    @Test
    @DisplayName("nothing may skip back to AWAITING_RECHARGE")
    void noUnCompleting() {
        // design-api.md §11 question 2: COMPLETED -> AWAITING_RECHARGE was
        // PROPOSED and rejected, in favour of two transitions that say which
        // thing went wrong.
        for (RechargeStatus s : RechargeStatus.values()) {
            assertFalse(s.canTransitionTo(AWAITING_RECHARGE),
                    s + " must not reopen a recharge");
        }
    }

    @Test
    @DisplayName("⚠ nothing may transition to VOIDED — it is defined and unreachable")
    void nothingReachesVoided() {
        // This replaces the assertion that VOIDED mandated a reason on entry.
        // There is no entry any more: COMPLETED -> VOIDED was removed on
        // 2026-10-21 and requiresReasonOnEntry() went with it, because a void
        // could not return money the customer had actually paid. It credited the
        // amount back to RCHG_PENDING and nothing could take it out again —
        // RechargeFailureResolutionService refuses every status but FAILED, and
        // heldPending has no date filter, so the stranded amount would sit on the
        // day summary for good. COMPLETED -> FAILED -> REFUNDED does the job and
        // posts the identical reversal.
        //
        // ⚠ The CONSTANT stays, because V9 seeds the ref_recharge_status row and
        // this enum must not drift from it. So the honest shape is a status that
        // is defined, terminal and unreachable — and this is the test that keeps
        // it that way. AWAITING_RECHARGE -> VOIDED in particular must not be
        // added: it was the alternative to removing void and it lost.
        for (RechargeStatus s : RechargeStatus.values()) {
            assertFalse(s.canTransitionTo(VOIDED),
                    s + " must not reach VOIDED; #19's endpoint was withdrawn, "
                            + "not merely unwired");
        }

        // Still terminal, and still mapped, so a row from any other source would
        // render honestly rather than crash a screen.
        assertTrue(VOIDED.isTerminal());
        assertEquals(FailureResolution.NONE, FailureResolution.of(VOIDED));
    }

    @Test
    @DisplayName("only COMPLETED counts toward the day's takings")
    void takings() {
        for (RechargeStatus s : RechargeStatus.values()) {
            assertEquals(s == COMPLETED, s.countsTowardTakings(), s.name());
        }
    }

    @Test
    @DisplayName("a null target is refused rather than NPE'd")
    void nullTarget() {
        assertFalse(COMPLETED.canTransitionTo(null));
    }

    @Test
    @DisplayName("the wire contract derives status and resolution from one column")
    void wireMapping() {
        // design-api.md §3.3's table, which presents two fields over a schema
        // that has one. See FailureResolution's javadoc for why this is derived
        // rather than stored.
        assertEquals(FailureResolution.NONE, FailureResolution.of(AWAITING_RECHARGE));
        assertEquals(FailureResolution.NONE, FailureResolution.of(COMPLETED));
        assertEquals(FailureResolution.NONE, FailureResolution.of(FAILED));
        assertEquals(FailureResolution.NONE, FailureResolution.of(VOIDED));
        assertEquals(FailureResolution.RETRIED, FailureResolution.of(RETRIED));
        assertEquals(FailureResolution.REFUNDED, FailureResolution.of(REFUNDED));

        assertEquals(AWAITING_RECHARGE, FailureResolution.wireStatus(AWAITING_RECHARGE));
        assertEquals(COMPLETED, FailureResolution.wireStatus(COMPLETED));
        assertEquals(VOIDED, FailureResolution.wireStatus(VOIDED));
        assertEquals(FAILED, FailureResolution.wireStatus(FAILED));
        assertEquals(FAILED, FailureResolution.wireStatus(RETRIED),
                "a retried failure surfaces as FAILED carrying resolution=RETRIED");
        assertEquals(FAILED, FailureResolution.wireStatus(REFUNDED));
    }

    @Test
    @DisplayName("the unresolved-failure filter is one status, not a pair")
    void unresolvedFilter() {
        // §3.2's endpoint #11 reads "status=FAILED, resolution=NONE". On this
        // schema that is exactly STATUS_CODE='FAILED', because a resolved failure
        // has moved to RETRIED or REFUNDED.
        assertFalse(FailureResolution.of(FAILED).isResolved());
        assertTrue(FailureResolution.of(RETRIED).isResolved());
        assertTrue(FailureResolution.of(REFUNDED).isResolved());
    }

    @Test
    @DisplayName("every enum constant is a seeded ref_recharge_status code")
    void matchesReferenceTable() {
        // V9 seeds exactly these six. If a seventh is ever added to either side,
        // this is the test that notices.
        assertEquals(Set.of("AWAITING_RECHARGE", "COMPLETED", "FAILED", "VOIDED",
                        "RETRIED", "REFUNDED"),
                EnumSet.allOf(RechargeStatus.class).stream()
                        .map(Enum::name).collect(java.util.stream.Collectors.toSet()));
    }

    private static Set<RechargeStatus> allowedFrom(RechargeStatus from) {
        Set<RechargeStatus> allowed = EnumSet.noneOf(RechargeStatus.class);
        for (RechargeStatus to : RechargeStatus.values()) {
            if (from.canTransitionTo(to)) {
                allowed.add(to);
            }
        }
        return allowed;
    }
}
