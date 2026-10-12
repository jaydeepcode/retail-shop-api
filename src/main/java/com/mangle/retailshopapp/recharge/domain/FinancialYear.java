package com.mangle.retailshopapp.recharge.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One Indian financial year: 1 April to 31 March (design-domain.md §9.1).
 *
 * <p>Derived, never stored, never a mode. §9.3 rejects both a stored
 * {@code FY_START_YEAR} column ("denormalisation whose only possible future is
 * drift") and a stored global active-year ("absolutely not" — the first time it
 * reaches the credit-balance query, 33 of 34 open balances vanish from the screen
 * and staff stop chasing debt).
 *
 * <p>{@code startMonth} is carried on the record rather than compiled in, because
 * it is read from {@code rc_setting['fy.start.month']} at startup with 4 as the
 * fallback. §9.1 records that the month was specified twice and inconsistently
 * before 2026-09-30: as {@code private static final int FY_START_MONTH = 4} in
 * one place and as "lives in rc_setting so it is data" in the prose. If it were
 * genuinely {@code static final} the seeded row would be decorative.
 * {@link com.mangle.retailshopapp.recharge.service.FinancialYears} is what reads
 * the setting; this type stays free of Spring so the arithmetic can be tested
 * against any start month.
 *
 * <p>Ranges are HALF-OPEN on purpose (§9.2): {@code >= start AND < end}, never
 * {@code BETWEEN}, so the 31-March-23:59:59.999 boundary can be neither lost nor
 * double-counted — and the predicate stays SARGable against
 * {@code idx_rech_dttm_status}.
 */
public record FinancialYear(int startYear, int startMonth) {

    public FinancialYear {
        if (startMonth < 1 || startMonth > 12) {
            throw new IllegalArgumentException("fy start month out of range: " + startMonth);
        }
    }

    /**
     * The financial year containing {@code date}, for a given start month.
     *
     * <p>Note this takes the date rather than reading a clock: §9.3's whole
     * argument is that the year must be a parameter the caller passes, so a
     * query that must not be year-scoped structurally cannot receive one.
     */
    public static FinancialYear of(LocalDate date, int startMonth) {
        int startYear = date.getMonthValue() >= startMonth ? date.getYear() : date.getYear() - 1;
        return new FinancialYear(startYear, startMonth);
    }

    /**
     * Parses the wire form {@code "2026-27"}.
     *
     * @throws IllegalArgumentException if the label is not two consecutive years
     */
    public static FinancialYear parse(String label, int startMonth) {
        if (label == null || !label.matches("^\\d{4}-\\d{2}$")) {
            throw new IllegalArgumentException("financial year must look like 2026-27: " + label);
        }
        int startYear = Integer.parseInt(label.substring(0, 4));
        int endShort = Integer.parseInt(label.substring(5));
        // "2026-28" is a plausible typo that would otherwise resolve silently to
        // FY 2026-27, so the two halves are checked against each other.
        if ((startYear + 1) % 100 != endShort) {
            throw new IllegalArgumentException(
                    "financial year halves are not consecutive years: " + label);
        }
        return new FinancialYear(startYear, startMonth);
    }

    public LocalDateTime startInclusive() {
        return LocalDate.of(startYear, startMonth, 1).atStartOfDay();
    }

    public LocalDateTime endExclusive() {
        return LocalDate.of(startYear + 1, startMonth, 1).atStartOfDay();
    }

    public LocalDate startDate() {
        return startInclusive().toLocalDate();
    }

    /** The last day INSIDE the year — for display only; never use it as a predicate. */
    public LocalDate endDate() {
        return endExclusive().toLocalDate().minusDays(1);
    }

    /**
     * The wire form, {@code "2026-27"}.
     *
     * <p>⚠ NO "FY " PREFIX, which differs from design-domain.md §9.1's snippet.
     * That snippet returns {@code "FY %d-%02d"}, but design-api.md §3.3's response
     * carries {@code "financialYear": "2026-27"} and §6.2 validates the
     * {@code ?financialYear=} parameter against {@code ^\d{4}-\d{2}$} — which
     * "FY 2026-27" fails. A request could not round-trip its own response. The
     * prefix is presentation, so it lives in {@link #displayLabel()}.
     */
    public String label() {
        return "%d-%02d".formatted(startYear, (startYear + 1) % 100);
    }

    /** For a screen heading. Never sent as {@code financialYear}. */
    public String displayLabel() {
        return "FY " + label();
    }

    public boolean contains(LocalDateTime when) {
        return when != null
                && !when.isBefore(startInclusive())
                && when.isBefore(endExclusive());
    }

    public FinancialYear previous() {
        return new FinancialYear(startYear - 1, startMonth);
    }
}
