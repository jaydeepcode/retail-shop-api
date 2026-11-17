package com.mangle.retailshopapp.settings;

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
    public static final String FLOAT_IMPS_FEE = "float.imps.fee";

    /**
     * ⚠ THE ONLY {@code 5.00} FOR THIS SETTING IN {@code src/main}, deliberately
     * — see {@link #changeDisplayThreshold()}.
     */
    private static final BigDecimal DEFAULT_CHANGE_DISPLAY_THRESHOLD = new BigDecimal("5.00");

    /**
     * The same figure as the threshold above and an unrelated setting, which is
     * why they are two constants rather than one shared field. {@code V9:220}
     * seeds the fee and {@code V9:229} the threshold; they coincide at ₹5 today
     * and nothing says they must.
     */
    private static final BigDecimal DEFAULT_FLOAT_IMPS_FEE = new BigDecimal("5.00");

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

    // ==================================================================
    // The two money figures that were written down in more than one place
    // ==================================================================

    /**
     * Below this, change is not displayed at the counter (log §14.5).
     *
     * <p>⚠ <b>THE FALLBACK LIVES HERE BECAUSE ₹5 WAS WRITTEN THREE TIMES</b>
     * (next-session-s5.md item 3). {@code V9:229} seeds the row; the recharge
     * counter read it through {@code OperatorConfigService}, which carried its
     * own {@code new BigDecimal("5.00")}, while {@code TicketResponseMapper} and
     * {@code tickets/booking/booking.component.ts} each carried the figure as a
     * literal because <b>there was no shared config endpoint to read it from</b>.
     * Both of those carried a comment saying so.
     *
     * <p>{@code GET /config} is that endpoint, and this is the one definition of
     * the default behind it: {@code OperatorConfigService.changeDisplayThreshold}
     * now delegates here and the ticket literal is gone, so the figure is
     * declared once in SQL and defaulted once in Java. ⚠ <b>A fourth literal is
     * what item 3 forbids</b> — a caller that needs this figure reads it from
     * here or from {@code /config}, never by writing it again.
     */
    @Transactional(readOnly = true)
    public BigDecimal changeDisplayThreshold() {
        return getDecimal(CHANGE_DISPLAY_THRESHOLD, DEFAULT_CHANGE_DISPLAY_THRESHOLD);
    }

    /**
     * The flat IMPS charge the shop bears on every float top-up (log §2.5).
     *
     * <p>Endpoint 19's {@code transferFee} default. The fee is posted as its OWN
     * expense leg and never folded into the float's cost basis: it is charged for
     * the transfer method rather than for the balance, and folding it would push
     * the float ratio off exactly 1.0 — which would end the only independent
     * check the design has on float (log §2.5:114-118).
     *
     * <p>It has a fallback, unlike {@link #BANK_DEFAULT_ACCOUNT}, and the two
     * cases genuinely differ. A missing bank row would make the server GUESS
     * which account money moves through, so it must fail loudly (log §9.3). A
     * missing fee row degrades to the figure the owner confirmed and the FRD
     * records — the same reasoning {@code fy.start.month} uses — and a client may
     * send {@code transferFee} explicitly anyway, which is how E7's cash top-up
     * expresses "no IMPS fee arises".
     */
    @Transactional(readOnly = true)
    public BigDecimal floatImpsFee() {
        return getDecimal(FLOAT_IMPS_FEE, DEFAULT_FLOAT_IMPS_FEE);
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
