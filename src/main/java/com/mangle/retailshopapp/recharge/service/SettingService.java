package com.mangle.retailshopapp.recharge.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mangle.retailshopapp.recharge.model.RcSetting;
import com.mangle.retailshopapp.recharge.repo.RcSettingRepository;

/**
 * Reads {@code rc_setting} (requirement 10: config in tables, with no screen).
 *
 * <p>⚠ FALLBACKS ARE PER KEY AND DELIBERATE, not a uniform convenience. Two of
 * them are decisions:
 *
 * <ul>
 *   <li>{@code fy.start.month} falls back to 4, so a missing row degrades to the
 *       correct Indian default (§9.1, log §8.7). This is the one reachable "no
 *       source found" state in the financial-year design and it degrades to the
 *       right answer.
 *   <li>{@code bank.default.account} has NO fallback (log §9.3). A missing row
 *       there is a configuration error that must fail loudly rather than quietly
 *       post to a guessed account — see {@link #requireString}.
 * </ul>
 *
 * <p>Not cached. Eight rows read a handful of times per sale is not a
 * performance problem, and a cache would mean an edit made in SQL — which is how
 * these are edited by design — did not take effect until a restart. That is a
 * worse failure than a query.
 */
@Service
public class SettingService {

    public static final String CREDIT_WARNING_DAYS = "credit.warning.days";
    public static final String FY_START_MONTH = "fy.start.month";
    public static final String CHANGE_DISPLAY_THRESHOLD = "change.display.threshold";
    public static final String SURCHARGE_DEFAULT_TV = "surcharge.default.tv";
    public static final String SURCHARGE_DEFAULT_MOBILE = "surcharge.default.mobile";
    public static final String CREDIT_LEDGER_CUSTOMERS = "credit.ledger.customers";
    public static final String BANK_DEFAULT_ACCOUNT = "bank.default.account";
    public static final String LEDGER_OPENING_DATE = "ledger.opening.date";

    private final RcSettingRepository settings;

    public SettingService(RcSettingRepository settings) {
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public Optional<String> findString(String key) {
        return settings.findBySettingKey(key).map(RcSetting::getSettingValue);
    }

    @Transactional(readOnly = true)
    public String getString(String key, String fallback) {
        return findString(key).orElse(fallback);
    }

    /**
     * For a key whose absence is a configuration error rather than a default.
     *
     * @throws IllegalStateException always, when the row is missing
     */
    @Transactional(readOnly = true)
    public String requireString(String key) {
        return findString(key).orElseThrow(() -> new IllegalStateException(
                "rc_setting['" + key + "'] is missing, and this key has no fallback "
                        + "on purpose — see log §9.3"));
    }

    @Transactional(readOnly = true)
    public int getInt(String key, int fallback) {
        return findString(key).map(Integer::parseInt).orElse(fallback);
    }

    @Transactional(readOnly = true)
    public BigDecimal getDecimal(String key, BigDecimal fallback) {
        return findString(key).map(BigDecimal::new).orElse(fallback);
    }

    /**
     * The customer ids that are ledger accounts rather than people (log §2.6).
     *
     * <p>Three rows: 269 Janata Sahakari Bank, 409 Hdfc Bank, 323 Bankof
     * India4425, ₹49,211 between them. They are {@code rs_cust_dtls} rows because
     * the legacy app had no concept of a UPI receipt, so money that had gone to
     * the bank was recorded against a "customer" named after it.
     *
     * <p>⚠ RETURNS A NON-EMPTY SENTINEL WHEN THE SETTING IS ABSENT OR EMPTY. The
     * list feeds a {@code NOT IN (...)} in the credit book, and an empty
     * collection makes that SQL invalid; worse, a silently-empty list would put
     * three bank accounts into the credit book as the largest debtors on the
     * screen. {@code -1} matches nothing and keeps the query valid.
     */
    @Transactional(readOnly = true)
    public List<Integer> ledgerCustomerIds() {
        String raw = getString(CREDIT_LEDGER_CUSTOMERS, "");
        List<Integer> ids = raw.isBlank() ? List.of()
                : java.util.Arrays.stream(raw.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(Integer::parseInt)
                        .toList();
        return ids.isEmpty() ? List.of(-1) : ids;
    }

    /**
     * Whether the ledger has been opened.
     *
     * <p>The ABSENCE of {@code ledger.opening.date} is how the system knows it has
     * not been (log §12), which is why no migration may seed it and why S3 must
     * not post a voucher to the developer restore.
     */
    @Transactional(readOnly = true)
    public boolean isLedgerOpen() {
        return findString(LEDGER_OPENING_DATE).isPresent();
    }
}
