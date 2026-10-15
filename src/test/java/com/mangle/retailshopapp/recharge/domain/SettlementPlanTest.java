package com.mangle.retailshopapp.recharge.domain;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.mangle.retailshopapp.recharge.domain.SettlementPlan.RechargeLine;
import com.mangle.retailshopapp.recharge.domain.SettlementPlan.SettlementLine;
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
 * The settle-basket matrix, with no database and no Spring context — which is
 * design-api.md §5.4 rule 4's whole claim about this type, made checkable.
 */
@DisplayName("SettlementPlan")
class SettlementPlanTest {

    private static RechargeLine mobile(String amount, String surcharge) {
        return new RechargeLine(ServiceType.MOBILE, "9000000002", "BSNL", "A1TOPUP",
                new BigDecimal(amount), new BigDecimal(surcharge));
    }

    private static RechargeLine tv(String amount, String surcharge) {
        return new RechargeLine(ServiceType.TV, "02700000001", "DISHTV", "A1TOPUP",
                new BigDecimal(amount), new BigDecimal(surcharge));
    }

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

    @Nested
    @DisplayName("the total and the change")
    class Totals {

        @Test
        @DisplayName("design-api.md §3.3's own worked example comes out exactly")
        void specExample() {
            // The example in §3.3: BSNL 219 + Dish TV 200 with a 10 surcharge,
            // settled 300 cash (500 tendered) + 100 UPI + 29 credit.
            SettlementPlan plan = SettlementPlan.of(
                    List.of(mobile("219.00", "0.00"), tv("200.00", "10.00")),
                    List.of(cash("300.00", "500.00"), upi("100.00"), credit("29.00", 403)));

            assertEquals(new BigDecimal("429.00"), plan.totalDue());
            assertEquals(new BigDecimal("500.00"), plan.cashTendered());
            assertEquals(new BigDecimal("200.00"), plan.changeDue());
            assertEquals(new BigDecimal("10.00"), plan.totalSurcharge());
            assertEquals(new BigDecimal("419.00"), plan.totalRechargeAmount());
        }

        @Test
        @DisplayName("the surcharge is added to the total but never into the amount")
        void surchargeIsItsOwnAmount() {
            SettlementPlan plan = SettlementPlan.of(
                    List.of(tv("240.00", "10.00")),
                    List.of(cash("250.00", "250.00")));

            assertEquals(new BigDecimal("250.00"), plan.totalDue(),
                    "the customer pays 250");
            assertEquals(new BigDecimal("240.00"), plan.totalRechargeAmount(),
                    "but the recharge is 240 — fold these together and no query can "
                            + "say whether a discrepancy is a wrong rate or a surcharge");
            assertEquals(new BigDecimal("10.00"), plan.totalSurcharge());
        }

        @Test
        @DisplayName("no cash line means no change and no tender")
        void noCashLine() {
            SettlementPlan plan = SettlementPlan.of(
                    List.of(mobile("199.00", "0.00")),
                    List.of(upi("199.00")));

            assertEquals(new BigDecimal("0.00"), plan.changeDue());
            assertEquals(new BigDecimal("0.00"), plan.cashTendered());
        }

        @Test
        @DisplayName("an absent amountTendered means exact cash, not zero")
        void absentTenderIsExact() {
            SettlementPlan plan = SettlementPlan.of(
                    List.of(mobile("199.00", "0.00")),
                    List.of(cash("199.00", null)));

            assertEquals(new BigDecimal("199.00"), plan.cashTendered());
            assertEquals(new BigDecimal("0.00"), plan.changeDue());
        }
    }

    @Nested
    @DisplayName("change display (log §14.5)")
    class ChangeDisplay {

        private static final BigDecimal THRESHOLD = new BigDecimal("5.00");

        @Test
        @DisplayName("a rupee of change is not displayed — 12,026 occurrences say so")
        void rupeeNotShown() {
            SettlementPlan plan = SettlementPlan.of(
                    List.of(mobile("199.00", "0.00")),
                    List.of(cash("199.00", "200.00")));

            assertEquals(new BigDecimal("1.00"), plan.changeDue(),
                    "the figure is still computed and recorded");
            assertFalse(plan.changeIsDisplayed(THRESHOLD),
                    "but it is not shown: there is no coin and the customer waves it away");
        }

        @Test
        @DisplayName("exactly at the threshold it IS displayed")
        void atThresholdShown() {
            SettlementPlan plan = SettlementPlan.of(
                    List.of(mobile("195.00", "0.00")),
                    List.of(cash("195.00", "200.00")));

            assertEquals(new BigDecimal("5.00"), plan.changeDue());
            assertTrue(plan.changeIsDisplayed(THRESHOLD),
                    "§14.5: shown plainly at Rs 5 and above");
        }

        @Test
        @DisplayName("real change from a note is displayed")
        void noteChangeShown() {
            SettlementPlan plan = SettlementPlan.of(
                    List.of(mobile("199.00", "0.00")),
                    List.of(cash("199.00", "500.00")));

            assertEquals(new BigDecimal("301.00"), plan.changeDue());
            assertTrue(plan.changeIsDisplayed(THRESHOLD));
        }

        @Test
        @DisplayName("zero change is not 'displayed' either")
        void zeroChange() {
            SettlementPlan plan = SettlementPlan.of(
                    List.of(mobile("199.00", "0.00")),
                    List.of(cash("199.00", "199.00")));

            assertFalse(plan.changeIsDisplayed(THRESHOLD));
        }
    }

    @Nested
    @DisplayName("the rules that refuse a basket")
    class Refusals {

        @Test
        @DisplayName("lines that fall short are refused, carrying both totals")
        void linesDoNotSum() {
            LinesDoNotSumException ex = assertThrows(LinesDoNotSumException.class, () ->
                    SettlementPlan.of(
                            List.of(mobile("219.00", "0.00"), tv("200.00", "10.00")),
                            List.of(cash("400.00", "400.00"))));

            assertEquals(ErrorCode.LINES_DO_NOT_SUM, ex.code());
            assertEquals(422, ex.status());
            assertEquals(new BigDecimal("429.00"), ex.details().get("expectedTotal"));
            assertEquals(new BigDecimal("400.00"), ex.details().get("providedTotal"),
                    "the UI shows the shortfall rather than just a refusal");
        }

        @Test
        @DisplayName("overpaying by line is refused too, not treated as change")
        void linesOverSum() {
            // Worth its own test: change comes from the TENDER, never from a line
            // that exceeds the total. Accepting this would let a basket book
            // income the customer never paid.
            assertThrows(LinesDoNotSumException.class, () ->
                    SettlementPlan.of(
                            List.of(mobile("199.00", "0.00")),
                            List.of(cash("199.00", "199.00"), upi("50.00"))));
        }

        @Test
        @DisplayName("a credit line with no customer is refused")
        void creditWithoutCustomer() {
            CreditRequiresCustomerException ex = assertThrows(
                    CreditRequiresCustomerException.class, () ->
                            SettlementPlan.of(
                                    List.of(mobile("199.00", "0.00")),
                                    List.of(cash("150.00", "150.00"), credit("49.00", null))));

            assertEquals(ErrorCode.CREDIT_REQUIRES_CUSTOMER, ex.code());
        }

        @Test
        @DisplayName("two credit lines are refused")
        void twoCreditLines() {
            TooManyCreditLinesException ex = assertThrows(TooManyCreditLinesException.class, () ->
                    SettlementPlan.of(
                            List.of(mobile("200.00", "0.00")),
                            List.of(credit("100.00", 403), credit("100.00", 287))));

            assertEquals(ErrorCode.TOO_MANY_CREDIT_LINES, ex.code());
        }

        @Test
        @DisplayName("a tender below its own cash line is refused")
        void shortTender() {
            InvalidTenderException ex = assertThrows(InvalidTenderException.class, () ->
                    SettlementPlan.of(
                            List.of(mobile("199.00", "0.00")),
                            List.of(cash("199.00", "150.00"))));

            assertEquals(ErrorCode.INVALID_TENDER, ex.code());
            assertEquals(new BigDecimal("150.00"), ex.details().get("amountTendered"));
        }

        @Test
        @DisplayName("tender on a UPI line is refused")
        void tenderOnNonCash() {
            SettlementLine upiWithTender = new SettlementLine(SettlementMethod.UPI,
                    new BigDecimal("199.00"), new BigDecimal("200.00"), null, null, null);

            assertThrows(InvalidTenderException.class, () ->
                    SettlementPlan.of(List.of(mobile("199.00", "0.00")), List.of(upiWithTender)));
        }

        @Test
        @DisplayName("two cash lines are refused — one drawer, one tender")
        void twoCashLines() {
            assertThrows(InvalidTenderException.class, () ->
                    SettlementPlan.of(
                            List.of(mobile("200.00", "0.00")),
                            List.of(cash("100.00", "100.00"), cash("100.00", "100.00"))));
        }

        @Test
        @DisplayName("an empty basket is refused")
        void emptyBasket() {
            assertThrows(InvalidTenderException.class, () ->
                    SettlementPlan.of(List.of(), List.of(cash("1.00", "1.00"))));
            assertThrows(InvalidTenderException.class, () ->
                    SettlementPlan.of(List.of(mobile("199.00", "0.00")), List.of()));
        }

        @Test
        @DisplayName("a zero or negative recharge is refused")
        void nonPositiveRecharge() {
            assertThrows(InvalidTenderException.class, () ->
                    SettlementPlan.of(List.of(mobile("0.00", "0.00")), List.of(cash("0.00", "0.00"))));
            assertThrows(InvalidTenderException.class, () ->
                    SettlementPlan.of(List.of(mobile("-199.00", "0.00")),
                            List.of(cash("1.00", "1.00"))));
        }

        @Test
        @DisplayName("the server never invents credit from a short basket")
        void noSilentCredit() {
            // §3.3 states this as a contract rule, and it is the one worth a test
            // of its own: a 199 basket settled with 150 cash is a 422, NOT a tab
            // quietly opened for 49 in nobody's name.
            assertThrows(LinesDoNotSumException.class, () ->
                    SettlementPlan.of(
                            List.of(mobile("199.00", "0.00")),
                            List.of(cash("150.00", "150.00"))));
        }

        @Test
        @DisplayName("money finer than a paisa is a programming error, not rounded away")
        void thirdDecimalRefused() {
            assertThrows(IllegalArgumentException.class, () ->
                    new RechargeLine(ServiceType.MOBILE, "9000000002", "BSNL", "A1TOPUP",
                            new BigDecimal("199.005"), BigDecimal.ZERO));
        }
    }

    @Nested
    @DisplayName("the credit line")
    class Credit {

        @Test
        @DisplayName("a basket with no credit reports none")
        void noCredit() {
            SettlementPlan plan = SettlementPlan.of(
                    List.of(mobile("199.00", "0.00")),
                    List.of(cash("199.00", "200.00")));

            assertFalse(plan.hasCredit());
            assertNull(plan.creditLine());
        }

        @Test
        @DisplayName("the credit line is found whatever order the lines arrive in")
        void creditFound() {
            SettlementLine theCredit = credit("49.00", 403);
            SettlementPlan plan = SettlementPlan.of(
                    List.of(mobile("199.00", "0.00")),
                    List.of(theCredit, cash("150.00", "150.00")));

            assertTrue(plan.hasCredit());
            assertSame(theCredit, plan.creditLine());
            assertEquals(403, plan.creditLine().customerId());
        }
    }

    @Nested
    @DisplayName("the basket shape the data actually shows (log §14.10)")
    class BasketShape {

        @Test
        @DisplayName("one recharge — 920 of 978 FY baskets")
        void one() {
            SettlementPlan plan = SettlementPlan.of(
                    List.of(mobile("299.00", "0.00")),
                    List.of(cash("299.00", "300.00")));
            assertEquals(1, plan.recharges().size());
        }

        @Test
        @DisplayName("two recharges — 57 of them, and the reason surcharge is per line")
        void two() {
            // With two recharges in one basket, a basket-level surcharge line
            // could not say which recharge the charge was for. That is why V28
            // puts SURCHARGE on rc_recharge rather than reusing history's
            // basket-level ANTV line.
            SettlementPlan plan = SettlementPlan.of(
                    List.of(mobile("199.00", "0.00"), tv("240.00", "10.00")),
                    List.of(cash("449.00", "500.00")));

            assertEquals(new BigDecimal("449.00"), plan.totalDue());
            assertEquals(new BigDecimal("10.00"), plan.totalSurcharge(),
                    "the TV line carries it; the mobile line does not");
        }

        @Test
        @DisplayName("the plan's lists cannot be mutated after validation")
        void immutable() {
            SettlementPlan plan = SettlementPlan.of(
                    List.of(mobile("199.00", "0.00")),
                    List.of(cash("199.00", "199.00")));

            assertThrows(UnsupportedOperationException.class, () ->
                    plan.recharges().add(mobile("1.00", "0.00")));
            assertThrows(UnsupportedOperationException.class, () ->
                    plan.settlementLines().add(cash("1.00", "1.00")));
        }
    }
}
