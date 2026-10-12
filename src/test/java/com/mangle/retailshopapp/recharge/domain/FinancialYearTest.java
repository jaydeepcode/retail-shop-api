package com.mangle.retailshopapp.recharge.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("FinancialYear")
class FinancialYearTest {

    private static final int APRIL = 4;

    @Test
    @DisplayName("April starts a new year; March belongs to the old one")
    void boundaries() {
        assertEquals(2026, FinancialYear.of(LocalDate.of(2026, 4, 1), APRIL).startYear());
        assertEquals(2026, FinancialYear.of(LocalDate.of(2027, 3, 31), APRIL).startYear());
        assertEquals(2025, FinancialYear.of(LocalDate.of(2026, 3, 31), APRIL).startYear());
        assertEquals(2026, FinancialYear.of(LocalDate.of(2026, 12, 31), APRIL).startYear());
    }

    @Test
    @DisplayName("the range is half-open, so the 31-March midnight boundary is exact")
    void halfOpenRange() {
        // §9.2 is explicit about >= ... < rather than BETWEEN: a BETWEEN on
        // datetime either loses 31-March-23:59:59.999 or double-counts it.
        FinancialYear fy = new FinancialYear(2026, APRIL);

        assertEquals(LocalDateTime.of(2026, 4, 1, 0, 0), fy.startInclusive());
        assertEquals(LocalDateTime.of(2027, 4, 1, 0, 0), fy.endExclusive());

        assertTrue(fy.contains(LocalDateTime.of(2026, 4, 1, 0, 0)),
                "the first instant is inside");
        assertTrue(fy.contains(LocalDateTime.of(2027, 3, 31, 23, 59, 59, 999_000_000)),
                "the last instant before midnight is inside");
        assertFalse(fy.contains(LocalDateTime.of(2027, 4, 1, 0, 0)),
                "midnight on 1 April belongs to the NEXT year, with no gap");
        assertFalse(fy.contains(LocalDateTime.of(2026, 3, 31, 23, 59, 59)));
    }

    @Test
    @DisplayName("the label is the wire form 2026-27, with no FY prefix")
    void label() {
        // ⚠ This differs from design-domain.md §9.1's snippet, which returns
        // "FY 2026-27". design-api.md §6.2 validates ?financialYear= against
        // ^\d{4}-\d{2}$, which that string fails — a request could not round-trip
        // its own response.
        assertEquals("2026-27", new FinancialYear(2026, APRIL).label());
        assertEquals("FY 2026-27", new FinancialYear(2026, APRIL).displayLabel());

        assertTrue(new FinancialYear(2026, APRIL).label().matches("^\\d{4}-\\d{2}$"),
                "the label must satisfy §6.2's own parameter pattern");
    }

    @Test
    @DisplayName("the century rolls over without producing 2099-100")
    void centuryRollover() {
        assertEquals("2099-00", new FinancialYear(2099, APRIL).label());
        assertEquals("2000-01", new FinancialYear(2000, APRIL).label());
    }

    @Test
    @DisplayName("a label round-trips")
    void roundTrip() {
        FinancialYear fy = new FinancialYear(2026, APRIL);
        assertEquals(fy, FinancialYear.parse(fy.label(), APRIL));
    }

    @Test
    @DisplayName("a label whose halves are not consecutive years is refused")
    void inconsistentLabel() {
        // "2026-28" would otherwise resolve silently to FY 2026-27 and the user
        // would get a year they did not ask for.
        assertThrows(IllegalArgumentException.class, () -> FinancialYear.parse("2026-28", APRIL));
        assertThrows(IllegalArgumentException.class, () -> FinancialYear.parse("2026", APRIL));
        assertThrows(IllegalArgumentException.class, () -> FinancialYear.parse("FY 2026-27", APRIL));
        assertThrows(IllegalArgumentException.class, () -> FinancialYear.parse(null, APRIL));
    }

    @Test
    @DisplayName("endDate is the last day inside the year")
    void endDate() {
        FinancialYear fy = new FinancialYear(2026, APRIL);
        assertEquals(LocalDate.of(2026, 4, 1), fy.startDate());
        assertEquals(LocalDate.of(2027, 3, 31), fy.endDate());
    }

    @Test
    @DisplayName("a non-April start month works, because the month is data not code")
    void configurableStartMonth() {
        // §9.1 records that the month was specified twice and inconsistently —
        // compiled in as static final in one place and described as living in
        // rc_setting in the prose. If it were genuinely static final the seeded
        // fy.start.month row would be decorative.
        FinancialYear calendar = FinancialYear.of(LocalDate.of(2026, 3, 31), 1);
        assertEquals(2026, calendar.startYear());
        assertEquals("2026-27", calendar.label());
        assertEquals(LocalDateTime.of(2026, 1, 1, 0, 0), calendar.startInclusive());

        assertThrows(IllegalArgumentException.class, () -> new FinancialYear(2026, 0));
        assertThrows(IllegalArgumentException.class, () -> new FinancialYear(2026, 13));
    }

    @Test
    @DisplayName("previous() steps back one year")
    void previous() {
        assertEquals(new FinancialYear(2025, APRIL), new FinancialYear(2026, APRIL).previous());
    }

    @Test
    @DisplayName("FY is derived from the payment date, so a basket stays whole")
    void derivedFromPaymentDate() {
        // §3.6 states this explicitly because it is the one place "derived from
        // each transaction's date" is ambiguous: a recharge COMPLETED in April
        // that was PAID for in March stays with its payment.
        LocalDateTime paidInMarch = LocalDateTime.of(2026, 3, 30, 23, 55);
        LocalDateTime completedInApril = LocalDateTime.of(2026, 4, 1, 0, 5);

        FinancialYear byPayment = FinancialYear.of(paidInMarch.toLocalDate(), APRIL);
        assertEquals(2025, byPayment.startYear());
        assertTrue(byPayment.contains(paidInMarch));
        assertFalse(byPayment.contains(completedInApril),
                "the completion falls in the next year — which is correct accrual, "
                        + "and why the grouping key is the payment");
    }
}
