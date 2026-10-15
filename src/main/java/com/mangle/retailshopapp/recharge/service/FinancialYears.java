package com.mangle.retailshopapp.recharge.service;

import java.time.Clock;
import java.time.LocalDate;

import org.springframework.stereotype.Service;

import com.mangle.retailshopapp.recharge.domain.FinancialYear;
import com.mangle.retailshopapp.recharge.exception.ErrorCode;
import com.mangle.retailshopapp.recharge.exception.RechargeBadRequestException;

/**
 * Turns a date or a label into a {@link FinancialYear} (design-domain.md §9.1).
 *
 * <p>The thin Spring-aware wrapper around a Spring-free value object: this reads
 * the start month from {@code rc_setting} and {@code FinancialYear} does the
 * arithmetic. §9.1 records that the month was specified twice and inconsistently
 * before 2026-09-30 — compiled in as {@code static final} in one place and
 * described as living in {@code rc_setting} in the prose — and that if it were
 * genuinely {@code static final} the seeded row would be decorative.
 *
 * <p>Read on every call rather than once at startup, so that editing the row —
 * which is how this config is edited — takes effect without a restart. §9.1 says
 * "read once at startup"; this reads per call because the cost is one indexed
 * primary-key probe and the alternative is a setting that lies until the next
 * deploy. Recorded as a deliberate difference rather than an oversight.
 */
@Service
public class FinancialYears {

    private static final int FALLBACK_START_MONTH = 4;

    private final SettingService settings;
    private final Clock clock;

    public FinancialYears(SettingService settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    public int startMonth() {
        return settings.getInt(SettingService.FY_START_MONTH, FALLBACK_START_MONTH);
    }

    public FinancialYear of(LocalDate date) {
        return FinancialYear.of(date, startMonth());
    }

    public FinancialYear current() {
        return of(LocalDate.now(clock));
    }

    /**
     * Resolves the {@code ?financialYear=} parameter, defaulting to the year
     * containing today (§3.2 endpoint #17).
     *
     * @throws RechargeBadRequestException 400, not 422: a malformed year is a bad
     *         request parameter rather than a business rule being broken
     */
    public FinancialYear resolve(String label) {
        if (label == null || label.isBlank()) {
            return current();
        }
        try {
            return FinancialYear.parse(label, startMonth());
        } catch (IllegalArgumentException e) {
            throw new RechargeBadRequestException(ErrorCode.INVALID_FINANCIAL_YEAR, e.getMessage());
        }
    }
}
