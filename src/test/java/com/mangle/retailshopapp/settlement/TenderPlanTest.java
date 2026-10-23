package com.mangle.retailshopapp.settlement;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.mangle.retailshopapp.recharge.exception.CreditRequiresCustomerException;
import com.mangle.retailshopapp.recharge.exception.ErrorCode;
import com.mangle.retailshopapp.recharge.exception.InvalidTenderException;
import com.mangle.retailshopapp.recharge.exception.LinesDoNotSumException;
import com.mangle.retailshopapp.recharge.exception.TooManyCreditLinesException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tender half, against no basket at all.
 *
 * <p>{@code SettlementPlanTest} already asserts these rules through a basket of
 * recharges and is the guard that the S4 split changed nothing. These are here
 * because a ticket is the second caller and will not re-test what it inherits:
 * a rule that only holds when recharges are what is being sold is not a rule a
 * ticket can rely on. Every total below is handed in rather than derived, which
 * is the whole point of the split (next-session-s4.md correction 2).
 */
@DisplayName("TenderPlan")
class TenderPlanTest {

    private static SettlementLine cash(String amount, String tendered) {
        return new SettlementLine(SettlementMethod.CASH, new BigDecimal(amount),
                tendered == null ? null : new BigDecimal(tendered), null, null, null);
    }

    private static SettlementLine upi(String amount) {
        return new SettlementLine(SettlementMethod.UPI, new BigDecimal(amount), null,
                "UPI/447182003921", null, null);
    }

    private static SettlementLine credit(String amount, Integer customerId) {
        return new SettlementLine(SettlementMethod.CREDIT, new BigDecimal(amount), null, null,
                customerId, "will pay Friday");
    }

    /** A ₹485 booking: ₹425 fare funded from BANK_SELF, ₹60 commission. */
    private static final BigDecimal TICKET_TOTAL = new BigDecimal("485.00");

    @Nested
    @DisplayName("a total it was given rather than one it worked out")
    class GivenTotal {

        @Test
        @DisplayName("a ticket's fare plus commission settles exactly like a basket")
        void ticketTotal() {
            TenderPlan plan = TenderPlan.of(
                    List.of(cash("485.00", "500.00")), TICKET_TOTAL);

            assertEquals(TICKET_TOTAL, plan.totalDue());
            assertEquals(new BigDecimal("500.00"), plan.cashTendered());
            assertEquals(new BigDecimal("15.00"), plan.changeDue());
        }

        @Test
        @DisplayName("flow 2 — commission only, nothing funded, and it still has to add up")
        void commissionOnly() {
            // The customer paid the platform directly, so the shop takes ₹60 and
            // there is no fare. FARE_AMOUNT IS NULL is the row's business; the
            // tender only knows the figure it was handed.
            TenderPlan plan = TenderPlan.of(List.of(cash("60.00", null)), new BigDecimal("60.00"));

            assertEquals(new BigDecimal("60.00"), plan.cashTendered(),
                    "an absent amountTendered means exact cash, not zero");
            assertEquals(new BigDecimal("0.00"), plan.changeDue());
        }

        @Test
        @DisplayName("the total is never re-derived from the lines")
        void totalIsNotDerived() {
            // Lines that sum to something else are a refusal, not a new total —
            // which is what makes the caller the single authority for the figure.
            LinesDoNotSumException ex = assertThrows(LinesDoNotSumException.class, () ->
                    TenderPlan.of(List.of(cash("400.00", "400.00")), TICKET_TOTAL));

            assertEquals(new BigDecimal("485.00"), ex.details().get("expectedTotal"));
            assertEquals(new BigDecimal("400.00"), ex.details().get("providedTotal"));
        }
    }

    @Nested
    @DisplayName("the rules that refuse a tender")
    class Refusals {

        @Test
        @DisplayName("a credit line with no customer is refused")
        void creditWithoutCustomer() {
            CreditRequiresCustomerException ex = assertThrows(
                    CreditRequiresCustomerException.class, () ->
                            TenderPlan.of(
                                    List.of(cash("425.00", "425.00"), credit("60.00", null)),
                                    TICKET_TOTAL));

            assertEquals(ErrorCode.CREDIT_REQUIRES_CUSTOMER, ex.code());
        }

        @Test
        @DisplayName("two credit lines are refused")
        void twoCreditLines() {
            TooManyCreditLinesException ex = assertThrows(TooManyCreditLinesException.class, () ->
                    TenderPlan.of(
                            List.of(credit("425.00", 403), credit("60.00", 287)), TICKET_TOTAL));

            assertEquals(ErrorCode.TOO_MANY_CREDIT_LINES, ex.code());
        }

        @Test
        @DisplayName("a tender below its own cash line is refused")
        void shortTender() {
            InvalidTenderException ex = assertThrows(InvalidTenderException.class, () ->
                    TenderPlan.of(List.of(cash("485.00", "400.00")), TICKET_TOTAL));

            assertEquals(ErrorCode.INVALID_TENDER, ex.code());
            assertEquals(new BigDecimal("400.00"), ex.details().get("amountTendered"));
        }

        @Test
        @DisplayName("tender on a UPI line is refused")
        void tenderOnNonCash() {
            SettlementLine upiWithTender = new SettlementLine(SettlementMethod.UPI,
                    TICKET_TOTAL, new BigDecimal("500.00"), null, null, null);

            assertThrows(InvalidTenderException.class, () ->
                    TenderPlan.of(List.of(upiWithTender), TICKET_TOTAL));
        }

        @Test
        @DisplayName("two cash lines are refused — one drawer, one tender")
        void twoCashLines() {
            assertThrows(InvalidTenderException.class, () ->
                    TenderPlan.of(
                            List.of(cash("425.00", "425.00"), cash("60.00", "60.00")),
                            TICKET_TOTAL));
        }

        @Test
        @DisplayName("no lines at all is refused")
        void noLines() {
            assertThrows(InvalidTenderException.class, () ->
                    TenderPlan.of(List.of(), TICKET_TOTAL));
            assertThrows(InvalidTenderException.class, () ->
                    TenderPlan.of(null, TICKET_TOTAL));
        }

        @Test
        @DisplayName("a zero or negative line is refused")
        void nonPositiveLine() {
            assertThrows(InvalidTenderException.class, () ->
                    TenderPlan.of(List.of(cash("0.00", "0.00")), new BigDecimal("0.00")));
            assertThrows(InvalidTenderException.class, () ->
                    TenderPlan.of(List.of(cash("-1.00", "-1.00")), new BigDecimal("-1.00")));
        }

        @Test
        @DisplayName("the server never invents credit from a short tender")
        void noSilentCredit() {
            // §3.3 as a contract rule, and it carries over unchanged: a ₹485
            // booking settled with ₹425 cash is a 422, NOT a tab quietly opened
            // for ₹60 in nobody's name.
            assertThrows(LinesDoNotSumException.class, () ->
                    TenderPlan.of(List.of(cash("425.00", "425.00")), TICKET_TOTAL));
        }

        @Test
        @DisplayName("money finer than a paisa is a programming error, not rounded away")
        void thirdDecimalRefused() {
            assertThrows(IllegalArgumentException.class, () ->
                    cash("485.005", null));
        }
    }

    @Nested
    @DisplayName("change display (log §14.5)")
    class ChangeDisplay {

        private static final BigDecimal THRESHOLD = new BigDecimal("5.00");

        @Test
        @DisplayName("a rupee of change is not displayed, but is still computed")
        void rupeeNotShown() {
            TenderPlan plan = TenderPlan.of(
                    List.of(cash("485.00", "486.00")), TICKET_TOTAL);

            assertEquals(new BigDecimal("1.00"), plan.changeDue());
            assertFalse(plan.changeIsDisplayed(THRESHOLD));
        }

        @Test
        @DisplayName("exactly at the threshold it IS displayed")
        void atThresholdShown() {
            TenderPlan plan = TenderPlan.of(
                    List.of(cash("485.00", "490.00")), TICKET_TOTAL);

            assertEquals(new BigDecimal("5.00"), plan.changeDue());
            assertTrue(plan.changeIsDisplayed(THRESHOLD));
        }

        @Test
        @DisplayName("zero change is not displayed either")
        void zeroChange() {
            TenderPlan plan = TenderPlan.of(List.of(cash("485.00", "485.00")), TICKET_TOTAL);
            assertFalse(plan.changeIsDisplayed(THRESHOLD));
        }
    }

    @Nested
    @DisplayName("the credit line")
    class Credit {

        @Test
        @DisplayName("a sale with no credit reports none")
        void noCredit() {
            TenderPlan plan = TenderPlan.of(List.of(cash("485.00", "500.00")), TICKET_TOTAL);

            assertFalse(plan.hasCredit());
            assertNull(plan.creditLine());
        }

        @Test
        @DisplayName("the credit line is found whatever order the lines arrive in")
        void creditFound() {
            SettlementLine theCredit = credit("60.00", 403);
            TenderPlan plan = TenderPlan.of(
                    List.of(theCredit, cash("425.00", "425.00")), TICKET_TOTAL);

            assertTrue(plan.hasCredit());
            assertSame(theCredit, plan.creditLine());
            assertEquals(403, plan.creditLine().customerId());
        }

        @Test
        @DisplayName("a split across all three methods adds up")
        void threeWaySplit() {
            TenderPlan plan = TenderPlan.of(
                    List.of(cash("200.00", "200.00"), upi("225.00"), credit("60.00", 403)),
                    TICKET_TOTAL);

            assertEquals(TICKET_TOTAL, plan.totalDue());
            assertEquals(new BigDecimal("0.00"), plan.changeDue());
        }
    }

    @Test
    @DisplayName("the lines cannot be mutated after validation")
    void immutable() {
        TenderPlan plan = TenderPlan.of(List.of(cash("485.00", "485.00")), TICKET_TOTAL);

        assertThrows(UnsupportedOperationException.class, () ->
                plan.lines().add(cash("1.00", "1.00")));
    }
}
