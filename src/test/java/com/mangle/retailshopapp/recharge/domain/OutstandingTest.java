package com.mangle.retailshopapp.recharge.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Outstanding")
class OutstandingTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);
    private static final LocalDateTime LONG_AGO = LocalDateTime.of(2025, 6, 6, 10, 0);

    @Nested
    @DisplayName("the sign convention")
    class Signs {

        @Test
        @DisplayName("a negative tally means the customer owes, shown positive")
        void negativeTallyIsDebt() {
            // §1.8 requires outstanding to be "a positive amount owed, whatever
            // SUM(req_amt)'s sign is". DBT rows are stored negative.
            Outstanding o = Outstanding.fromTally(new BigDecimal("-49.00"), LONG_AGO);

            assertTrue(o.owes());
            assertFalse(o.inCredit());
            assertEquals(new BigDecimal("49.00"), o.owed());
            assertEquals(new BigDecimal("0.00"), o.advanceHeld());
        }

        @Test
        @DisplayName("a positive tally means the customer is in credit — both real cases")
        void positiveTallyIsCredit() {
            // log §2.8 and §14.2: customers 287 and 403 hold +Rs 37 and +Rs 15.
            // §7.1(b) warns a 'who owes us' screen filtering < 0 "silently loses
            // them", and §14.2 records the same Rs 52 being counted twice in
            // opposite directions in a SEALED opening journal.
            Outstanding thirtySeven = Outstanding.fromTally(new BigDecimal("37.00"), null);
            Outstanding fifteen = Outstanding.fromTally(new BigDecimal("15.00"), null);

            assertTrue(thirtySeven.inCredit());
            assertFalse(thirtySeven.owes());
            assertEquals(new BigDecimal("37.00"), thirtySeven.advanceHeld());
            assertEquals(new BigDecimal("15.00"), fifteen.advanceHeld());

            assertEquals(new BigDecimal("52.00"),
                    thirtySeven.advanceHeld().add(fifteen.advanceHeld()),
                    "the Rs 52 that §14.2 found double-counted");
        }

        @Test
        @DisplayName("a clear tab is neither owed nor held")
        void zeroTally() {
            Outstanding o = Outstanding.fromTally(BigDecimal.ZERO, LONG_AGO);

            assertFalse(o.owes());
            assertFalse(o.inCredit());
            assertNull(o.oldestOpenAt(), "nothing open means nothing to age");
        }

        @Test
        @DisplayName("a tab cannot be owed and held at once")
        void notBoth() {
            assertThrows(IllegalArgumentException.class, () ->
                    new Outstanding(new BigDecimal("10.00"), new BigDecimal("5.00"), null));
        }

        @Test
        @DisplayName("netPosition is positive when owed, negative when held")
        void netPosition() {
            assertEquals(new BigDecimal("49.00"),
                    Outstanding.fromTally(new BigDecimal("-49.00"), LONG_AGO).netPosition());
            assertEquals(new BigDecimal("-37.00"),
                    Outstanding.fromTally(new BigDecimal("37.00"), null).netPosition());
        }
    }

    @Nested
    @DisplayName("ageing and the warning")
    class Ageing {

        @Test
        @DisplayName("the age is measured from the oldest OPEN debt")
        void age() {
            Outstanding o = Outstanding.fromTally(new BigDecimal("-49.00"), LONG_AGO);
            assertEquals(493, o.ageInDays(TODAY));
        }

        @Test
        @DisplayName("past 30 days it warns; inside 30 days it does not")
        void warn() {
            Outstanding old = Outstanding.fromTally(new BigDecimal("-49.00"), LONG_AGO);
            assertTrue(old.warn(TODAY, 30));

            Outstanding recent = Outstanding.fromTally(new BigDecimal("-49.00"),
                    LocalDateTime.of(2026, 10, 1, 9, 0));
            assertEquals(11, recent.ageInDays(TODAY));
            assertFalse(recent.warn(TODAY, 30));
        }

        @Test
        @DisplayName("exactly at the threshold it warns")
        void atThreshold() {
            Outstanding o = Outstanding.fromTally(new BigDecimal("-49.00"),
                    LocalDateTime.of(2026, 9, 12, 9, 0));
            assertEquals(30, o.ageInDays(TODAY));
            assertTrue(o.warn(TODAY, 30));
        }

        @Test
        @DisplayName("a customer in credit has no age and never warns")
        void creditDoesNotWarn() {
            Outstanding o = Outstanding.fromTally(new BigDecimal("37.00"), null);
            assertNull(o.ageInDays(TODAY));
            assertFalse(o.warn(TODAY, 30));
        }
    }

    @Nested
    @DisplayName("charging and receiving (log §2.8)")
    class Movements {

        private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 12, 11, 30);

        @Test
        @DisplayName("a charge against a clear tab becomes debt and starts the clock")
        void chargeClearTab() {
            Outstanding after = Outstanding.clear().charge(new BigDecimal("49.00"), NOW);

            assertTrue(after.owes());
            assertEquals(new BigDecimal("49.00"), after.owed());
            assertEquals(NOW, after.oldestOpenAt());
        }

        @Test
        @DisplayName("a charge consumes an advance first — the mirror of §2.8's rule")
        void chargeConsumesAdvance() {
            // This is what lets the two customers holding +Rs 37 and +Rs 15 spend
            // it, with no fourth payment method and no customer identification
            // added to otherwise anonymous sales.
            Outstanding held = Outstanding.fromTally(new BigDecimal("37.00"), null);

            Outstanding partly = held.charge(new BigDecimal("20.00"), NOW);
            assertTrue(partly.inCredit());
            assertEquals(new BigDecimal("17.00"), partly.advanceHeld());
            assertFalse(partly.owes());

            Outstanding exactly = held.charge(new BigDecimal("37.00"), NOW);
            assertFalse(exactly.owes());
            assertFalse(exactly.inCredit());

            Outstanding beyond = held.charge(new BigDecimal("50.00"), NOW);
            assertTrue(beyond.owes());
            assertEquals(new BigDecimal("13.00"), beyond.owed(),
                    "50 charged against 37 held leaves 13 owed");
            assertEquals(NOW, beyond.oldestOpenAt(), "the debt starts now");
        }

        @Test
        @DisplayName("a second charge does not reset the ageing clock")
        void chargeKeepsOldestDate() {
            // The 30-day warning is about the OLDEST open debt. If a new charge
            // reset it, a customer owing since 2025 would look current after one
            // more recharge — and staff would stop chasing.
            Outstanding open = Outstanding.fromTally(new BigDecimal("-49.00"), LONG_AGO);
            Outstanding after = open.charge(new BigDecimal("99.00"), NOW);

            assertEquals(new BigDecimal("148.00"), after.owed());
            assertEquals(LONG_AGO, after.oldestOpenAt());
            assertTrue(after.warn(TODAY, 30));
        }

        @Test
        @DisplayName("a receipt larger than the debt is held as an advance, not refused")
        void receiptBecomesAdvance() {
            // §2.8's own example: Rs 120 owed, the customer hands over Rs 200,
            // Rs 80 held against future trips — "decided by arithmetic rather
            // than by asking the staff member". design-api.md §3.2 removed the
            // 422 that used to refuse this.
            Outstanding owed = Outstanding.fromTally(new BigDecimal("-120.00"), LONG_AGO);
            Outstanding after = owed.receive(new BigDecimal("200.00"));

            assertFalse(after.owes());
            assertTrue(after.inCredit());
            assertEquals(new BigDecimal("80.00"), after.advanceHeld());
            assertNull(after.oldestOpenAt(), "nothing is open, so nothing ages");
        }

        @Test
        @DisplayName("a part payment leaves the clock where it was")
        void partPayment() {
            Outstanding owed = Outstanding.fromTally(new BigDecimal("-120.00"), LONG_AGO);
            Outstanding after = owed.receive(new BigDecimal("20.00"));

            assertEquals(new BigDecimal("100.00"), after.owed());
            assertEquals(LONG_AGO, after.oldestOpenAt(), "FIFO: the oldest debt is still open");
            assertTrue(after.warn(TODAY, 30));
        }

        @Test
        @DisplayName("a receipt that exactly clears the tab leaves it clear")
        void exactPayment() {
            Outstanding after = Outstanding.fromTally(new BigDecimal("-120.00"), LONG_AGO)
                    .receive(new BigDecimal("120.00"));

            assertFalse(after.owes());
            assertFalse(after.inCredit());
            assertNull(after.oldestOpenAt());
        }

        @Test
        @DisplayName("non-positive movements are refused")
        void nonPositive() {
            assertThrows(IllegalArgumentException.class, () ->
                    Outstanding.clear().charge(BigDecimal.ZERO, NOW));
            assertThrows(IllegalArgumentException.class, () ->
                    Outstanding.clear().receive(new BigDecimal("-1.00")));
        }

        @Test
        @DisplayName("money finer than a paisa is refused with a message")
        void paisaOnly() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    Outstanding.fromTally(new BigDecimal("-49.005"), LONG_AGO));
            assertTrue(ex.getMessage().contains("paisa"), ex.getMessage());
        }
    }
}
