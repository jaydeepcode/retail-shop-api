package com.mangle.retailshopapp.accounting;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mangle.retailshopapp.accounting.model.response.AccountResponse;
import com.mangle.retailshopapp.accounting.repo.AccAccountRepository;

/**
 * The chart of accounts as a picker reads it — endpoint 12 of
 * design-ledger-api.md §1.3.
 *
 * <p>A service of its own rather than a method on {@link LedgerCloseService},
 * which is 900 lines about counting drawers, or on {@code ManualVoucherService},
 * which is about writing vouchers. This answers "which accounts exist", and that
 * is a third question.
 *
 * <p>⚠ <b>READ-ONLY, AND THERE IS NO ACCOUNT CRUD TO GO WITH IT</b> (§6). The
 * chart of accounts is a V20/V21 seed plus a migration when a business is added;
 * a screen for it "would invite accounts that no posting rule knows about". So
 * this class will never grow a write.
 */
@Service
public class AccountDirectoryService {

    private final AccAccountRepository accounts;

    public AccountDirectoryService(AccAccountRepository accounts) {
        this.accounts = accounts;
    }

    /**
     * Active accounts, in {@code SORT_ORDER}, filtered as §1.3 allows.
     *
     * <p>⚠ AN UNKNOWN {@code type} OR {@code business} RETURNS AN EMPTY LIST
     * RATHER THAN A 400, which is deliberate and is why §1.3 gives this endpoint
     * the single code 200. The values are seeded reference data
     * ({@code ref_account_type}, {@code ref_business}), so validating them here
     * would mean a second list of the same constants in Java — exactly the
     * duplication {@code FUNDING_ACCOUNTS} already demonstrates the cost of
     * (next-session-s5.md, "smaller things"). An empty picker is also a visible
     * failure, where a 400 on a screen's startup call is a blank page.
     */
    @Transactional(readOnly = true)
    public List<AccountResponse> directory(boolean manualOnly, String type, String business) {
        return accounts.findDirectory(manualOnly, blankToNull(type), blankToNull(business))
                .stream()
                .map(AccountResponse::from)
                .toList();
    }

    /**
     * {@code ?type=} with nothing after it arrives as the empty string, not as
     * null, and would then match no account at all — a filter nobody asked for.
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
