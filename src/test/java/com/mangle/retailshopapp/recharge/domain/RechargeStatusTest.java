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
        assertEquals(EnumSet.of(VOIDED, FAILED), allowedFrom(COMPLETED));
        assertEquals(EnumSet.of(RETRIED, REFUNDED), allowedFrom(FAILED));
        assertEquals(EnumSet.noneOf(RechargeStatus.class), allowedFrom(VOIDED));
        assertEquals(EnumSet.noneOf(RechargeStatus.class), allowedFrom(RETRIED));
        assertEquals(EnumSet.noneOf(RechargeStatus.class), allowedFrom(REFUNDED));
    }

    @Test
    @DisplayName("COMPLETED is not terminal and VOIDED is — log §7.1's correction")
    void completedIsNotTerminal() {
        // This reverses what design-api.md §3.3 said before 2026-09-29. The two
        // arrows out of COMPLETED differ in kind on purpose: VOIDED means the
        // sale should not exist, FAILED means it was recorded right but never
        // delivered.
        assertFalse(COMPLETED.isTerminal());
        assertTrue(COMPLETED.canTransitionTo(VOIDED));
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
    @DisplayName("⚠ VOIDED mandates a reason and FAILED does not — one status, not two")
    void reasonIsMandatedByOneStatusOnly() {
        // The javadoc on requiresReasonOnEntry claimed two statuses while the
        // body returned one, and nothing called it — so the enum claimed to own a
        // rule that voidRecharge had hardcoded for itself (review finding 11).
        //
        // log §7.1 attaches the requirement to VOIDED alone: "one new terminal
        // status, reachable only from COMPLETED, with a mandatory reason", and the
        // argument is specific to it — a void must not become a back door for
        // refunding a delivered recharge, and with v1 single-user (FRD §3) there
        // is no second approver, so the reason IS the control.
        //
        // ⚠ V12__Recharge_tables.sql's comment says both VOIDED and FAILED
        // require one. That comment is wrong, and is deliberately not corrected in
        // place: V12 is applied, and editing it would change its Flyway checksum.
        // This assertion is where the rule is settled.
        assertTrue(VOIDED.requiresReasonOnEntry());

        for (RechargeStatus s : RechargeStatus.values()) {
            if (s != VOIDED) {
                assertFalse(s.requiresReasonOnEntry(),
                        s + " must not mandate a reason; design-api.md §3.2's "
                                + "MarkFailedRequest takes an OPTIONAL one");
            }
        }
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
