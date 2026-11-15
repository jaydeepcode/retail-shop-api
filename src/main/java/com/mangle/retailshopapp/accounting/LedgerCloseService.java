package com.mangle.retailshopapp.accounting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.mangle.retailshopapp.accounting.model.AccAccount;
import com.mangle.retailshopapp.accounting.model.AccReconciliation;
import com.mangle.retailshopapp.accounting.model.AccVoucher;
import com.mangle.retailshopapp.accounting.model.AccVoucherLine;
import com.mangle.retailshopapp.accounting.model.request.ExplainedVariance;
import com.mangle.retailshopapp.accounting.model.request.PoolCloseRequest;
import com.mangle.retailshopapp.accounting.model.request.ReconciliationRequest;
import com.mangle.retailshopapp.accounting.model.response.ClosePreviewResponse;
import com.mangle.retailshopapp.accounting.model.response.PoolCloseResponse;
import com.mangle.retailshopapp.accounting.model.response.ReconciliationResponse;
import com.mangle.retailshopapp.accounting.repo.AccAccountRepository;
import com.mangle.retailshopapp.accounting.repo.AccBalanceRepository;
import com.mangle.retailshopapp.accounting.repo.AccReconciliationRepository;
import com.mangle.retailshopapp.accounting.repo.AccVoucherLineRepository;
import com.mangle.retailshopapp.accounting.repo.AccVoucherRepository;

/**
 * The day close — endpoints 1 to 4 of design-ledger-api.md §1.1.
 *
 * <h2>A1: one POST, not a count plus a separate sweep</h2>
 *
 * Counting, the variance voucher and the evening sweep to {@code CASH_SAFE}
 * happen in ONE transaction, because they are one physical act and a count that
 * lands without its sweep leaves the tills non-zero overnight (log §3).
 *
 * <h2>⚠ THE ORDERING IS LOAD-BEARING, AND NOT FOR THE REASON THE OLDER
 * DOCUMENTS GIVE</h2>
 *
 * The {@code acc_reconciliation} rows are written BEFORE the variance and sweep
 * vouchers. Earlier drafts of §1.1 and of next-session-s5.md explained this as
 * "trigger 4 keys off {@code RECON_DATE >= VOUCHER_DATE}, so writing them first
 * means the close's own vouchers are still accepted" — i.e. that a reversed
 * ordering would make the close refuse its own sweep. <b>That reason is
 * wrong.</b> There is no trigger 4: it was removed on 2026-10-09 and now lives
 * in {@link LedgerCorrectionRules#assertVoucherDeletable(long)}, it was
 * {@code BEFORE DELETE} even when it existed, and <b>nothing has ever checked
 * reconciliation on an insert.</b> A reversed ordering posts perfectly happily.
 *
 * <p>The real reason is {@code LEDGER_AMOUNT}. It is <i>"the derived balance as
 * it stood BEFORE the variance journal"</i> (design-ledger.md:584, V22:146-152).
 * Post the sweep first and every member's derived balance is {@code 0.00} by the
 * time the row is written — so the row records {@code 0.00} against the counted
 * figure, the variance comes out as the whole count, and <b>the evidence of what
 * the drawer actually held is gone permanently</b>, because vouchers are
 * append-only and the pre-sweep balance is no longer recoverable from them.
 *
 * <p>⚠ So the test for this asserts {@code LEDGER_AMOUNT}, not a refusal. A test
 * written to the refusal passes whichever order you use, which is exactly how
 * the wrong reason survived three documents.
 *
 * <h2>Idempotency: the unique key IS the key</h2>
 *
 * Endpoints 2 and 4 take no {@code Idempotency-Key} header (§1.3). "Close
 * POOL-1 for 9 Nov" is true at most once, so the key is derived —
 * {@code POOL_CLOSE:<poolCode>:<reconDate>} and
 * {@code ACCT_RECON:<accountCode>:<reconDate>} — and
 * {@code uk_recon_account_date (ACCOUNT_CODE, RECON_DATE)} enforces it. The rows
 * go in first, the unique key refuses a duplicate, and the controller catches
 * that and re-reads the existing close in a FRESH read-only transaction, because
 * the aborted one cannot see its own rolled-back write.
 *
 * <p>The derived value is also stored on {@code acc_voucher.IDEMPOTENCY_KEY}, so
 * a replay can find the vouchers again without a second mapping table. Endpoint 2
 * posts TWO vouchers under one identity and {@code uk_vch_idem} is a single
 * column, so those two carry {@code :VARIANCE} and {@code :SWEEP} suffixes —
 * following {@code TICKET_BOOKING:tkt_booking#<id>:CREATED}. Endpoint 4 posts at
 * most one and stores the bare key, which is what §1.3's table spells.
 */
@Service
public class LedgerCloseService {

    /**
     * ⚠ The seed puts {@code CASH_SAFE} in a pool and the seed is what is wrong —
     * {@code adr/0002-cash-safe-closes-as-an-account-not-a-pool.md}.
     * {@code V21:331} sets {@code CASH_POOL = 'SAFE'}, while endpoint 4 is
     * specified for an account "not in a pool: {@code CASH_SAFE}, a bank, a
     * float", so the safe is closeable two ways and both would write the same
     * {@code uk_recon_account_date} row.
     *
     * <p>Closing it as a pool of one would seal a {@code Dr CASH_SAFE /
     * Cr CASH_SAFE} contra every single night: it balances, it passes every
     * check, it moves nothing, and it accumulates 365 times a year in the one
     * account the owner reads most. So {@code SAFE} is not treated as a pool
     * anywhere here — the preview files the safe under single accounts, and
     * {@link #closePool} refuses the code with a 422 naming endpoint 4.
     *
     * <p>Clearing the column is an {@code UPDATE} and S5 is not raising the
     * Flyway cap for it, so the correction rides with S6's opening-balance
     * migrations. Until then <b>this 422 is the only thing standing between the
     * seed and a nightly self-contra</b>, which is why it has a test that has
     * been watched to fail rather than a code comment.
     */
    static final String POOL_SAFE = "SAFE";

    static final String CASH_SAFE = "CASH_SAFE";
    static final String EXP_CASH_SHORT = "EXP_CASH_SHORT";
    static final String INC_CASH_OVER = "INC_CASH_OVER";
    static final String INC_RCHG_COMM = "INC_RCHG_COMM";

    /** {@code ref_voucher_source}: 'Cash count variance' (V21:181). */
    static final String SOURCE_CASH_RECON = "CASH_RECON";

    /**
     * Scale 2, so a figure that is only ever zero still compares equal to what
     * {@code decimal(38,2)} holds and reads as {@code 0.00} rather than {@code 0}
     * on the wire. {@code settlement.Amounts.ZERO} says the same thing, but that
     * package is about tender and allocation and a close has no tender, so this
     * is a constant rather than an import.
     */
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    /**
     * POOL-2 cannot balance until crockery is recorded (log §3, V21:287-289):
     * crockery cash is already in the water drawer and no crockery sale is
     * posted anywhere, so the count will always exceed the recorded balance by
     * that day's crockery takings.
     *
     * <p>⚠ TESTED AGAINST THE DATA RATHER THAN HARDCODED FOREVER. The blocker is
     * that {@code CASH_CROCKERY} does not exist yet; phase 2 adds it as POOL-2's
     * non-primary member, and on that day this check starts answering
     * {@code true} on its own. Naming the pool is unavoidable — nothing in the
     * schema records a membership that is still planned — but the condition is
     * not frozen.
     *
     * <p>And it lives in the PREVIEW, not in {@link #closePool}. §1.1 is explicit
     * that the server states the fact and the screen owns the wording: the close
     * endpoint stays generic and will close POOL-2 if asked, and the screen is
     * what declines to offer it.
     */
    static final String POOL_AWAITING_CROCKERY = "POOL-2";
    static final String CASH_CROCKERY = "CASH_CROCKERY";
    static final String REASON_CROCKERY_NOT_RECORDED = "CROCKERY_NOT_RECORDED";

    private final AccAccountRepository accounts;
    private final AccReconciliationRepository reconciliations;
    private final AccBalanceRepository balances;
    private final AccVoucherRepository vouchers;
    private final AccVoucherLineRepository voucherLines;
    private final LedgerPostingService postings;
    private final Clock clock;

    public LedgerCloseService(AccAccountRepository accounts,
                              AccReconciliationRepository reconciliations,
                              AccBalanceRepository balances,
                              AccVoucherRepository vouchers,
                              AccVoucherLineRepository voucherLines,
                              LedgerPostingService postings,
                              Clock clock) {
        this.accounts = accounts;
        this.reconciliations = reconciliations;
        this.balances = balances;
        this.vouchers = vouchers;
        this.voucherLines = voucherLines;
        this.postings = postings;
        this.clock = clock;
    }

    // ==================================================================
    // Endpoint 1 — GET /accounts/close-preview
    // ==================================================================

    /**
     * Everything closeable tonight, in one call.
     *
     * <p>Partitioned by {@code CASH_POOL}, with {@code SAFE} deliberately not
     * counting as a pool — see {@link #POOL_SAFE}.
     */
    @Transactional(readOnly = true)
    public ClosePreviewResponse preview() {
        List<AccAccount> reconcilable = accounts.findByReconcilableTrueAndActiveTrueOrderBySortOrder();
        List<String> codes = reconcilable.stream().map(AccAccount::getAccountCode).toList();

        Map<String, BigDecimal> derived = derivedBalances(codes);
        Map<String, AccReconciliation> newest = newestCloses(codes);

        Map<String, List<AccAccount>> byPool = new LinkedHashMap<>();
        List<ClosePreviewResponse.AccountPreview> singles = new ArrayList<>();
        for (AccAccount a : reconcilable) {
            if (isPooled(a)) {
                byPool.computeIfAbsent(a.getCashPool(), k -> new ArrayList<>()).add(a);
            } else {
                AccReconciliation last = newest.get(a.getAccountCode());
                singles.add(new ClosePreviewResponse.AccountPreview(
                        a.getAccountCode(),
                        a.getDisplayName(),
                        derived.get(a.getAccountCode()),
                        last == null ? null : last.getClosedAt(),
                        last == null ? null : last.getReconDate()));
            }
        }

        boolean crockeryRecorded = accounts.existsById(CASH_CROCKERY);
        List<ClosePreviewResponse.PoolPreview> pools = new ArrayList<>();
        for (Map.Entry<String, List<AccAccount>> e : byPool.entrySet()) {
            List<AccAccount> members = e.getValue();
            BigDecimal expected = ZERO;
            List<ClosePreviewResponse.MemberPreview> memberViews = new ArrayList<>();
            for (AccAccount m : members) {
                BigDecimal balance = derived.get(m.getAccountCode());
                expected = expected.add(balance);
                memberViews.add(new ClosePreviewResponse.MemberPreview(
                        m.getAccountCode(), m.getDisplayName(), m.isPoolPrimary(), balance));
            }

            // Both date fields come off ONE row, and that row is the primary
            // member's: A2 writes a row per member in the same transaction, so
            // any member would do, but the primary is the one that carries the
            // variance and so the one a reader will look for.
            AccReconciliation last = members.stream()
                    .filter(AccAccount::isPoolPrimary)
                    .findFirst()
                    .map(m -> newest.get(m.getAccountCode()))
                    .orElse(null);

            boolean awaitingCrockery =
                    POOL_AWAITING_CROCKERY.equals(e.getKey()) && !crockeryRecorded;
            pools.add(new ClosePreviewResponse.PoolPreview(
                    e.getKey(),
                    expected,
                    last == null ? null : last.getClosedAt(),
                    last == null ? null : last.getReconDate(),
                    !awaitingCrockery,
                    awaitingCrockery ? REASON_CROCKERY_NOT_RECORDED : null,
                    memberViews));
        }

        return new ClosePreviewResponse(pools, singles);
    }

    // ==================================================================
    // Endpoint 2 — POST /accounts/pools/{poolCode}/closes
    // ==================================================================

    /**
     * The day close: records the count, posts the variance if any, sweeps the
     * tills to {@code CASH_SAFE}. One transaction (A1).
     *
     * @throws LedgerPostingException on any of §1.1's 422 conditions, and on
     *         {@code poolCode = SAFE} (ADR 0002)
     */
    @Transactional
    public PoolCloseResponse closePool(String poolCode, PoolCloseRequest request,
                                       Integer staffUserId) {
        if (POOL_SAFE.equals(poolCode)) {
            // The one special case, and it is about the SEED rather than about
            // the pool — see POOL_SAFE above.
            throw new LedgerPostingException(
                    "CASH_SAFE closes as a single account, not as a pool: POST "
                            + "/accounts/CASH_SAFE/reconciliations (endpoint 4). V21:331 seeds it "
                            + "into pool SAFE, which is a seeding error corrected in S6; closing "
                            + "it here would post a Dr CASH_SAFE / Cr CASH_SAFE contra every "
                            + "night that balances and moves nothing.");
        }

        List<AccAccount> members = accounts.findByCashPoolAndActiveTrueOrderBySortOrder(poolCode);
        if (members.isEmpty()) {
            throw new LedgerPostingException("no such cash pool: " + poolCode);
        }
        AccAccount primary = solePrimaryOf(poolCode, members);

        requireNotInFuture(request.reconDate());
        require(request.countedAmount().signum() >= 0,
                "countedAmount cannot be negative: " + request.countedAmount());

        List<String> memberCodes = members.stream().map(AccAccount::getAccountCode).toList();
        Map<String, BigDecimal> ledger = derivedBalances(memberCodes);

        BigDecimal expected = ZERO;
        for (String code : memberCodes) {
            BigDecimal balance = ledger.get(code);
            // ⚠ NEGATIVE BEFORE THE CLOSE IS A GENUINE FAULT, and this is the
            // right place to say so — a till cannot hold less than nothing, so
            // something upstream posted a credit it had no cash for. After step 4
            // a negative member is ordinary (see postSweep), which is exactly why
            // the two cases are checked separately rather than once in the sweep.
            require(balance.signum() >= 0,
                    code + " records " + balance + " before the close, and a till cannot hold "
                            + "less than nothing. Something posted against it without the cash "
                            + "to back it; correct that with a JOURNAL before counting the "
                            + "drawer.");
            expected = expected.add(balance);
        }
        BigDecimal variance = request.countedAmount().subtract(expected);
        ResolvedExplanation explanation = resolveExplanation(request.explained(), variance, null);

        LocalDateTime now = LocalDateTime.now(clock);
        String key = poolCloseKey(poolCode, request.reconDate());

        // --- Step 2: the rows, BEFORE any voucher. See the class comment. -----
        // Non-primary members carry their own derived balance in both columns, so
        // their variance is 0.00 by construction; the primary carries the
        // physical count minus the others, so the pool's whole variance lands
        // there without a rule in code (A2, E17d).
        BigDecimal othersTotal = ZERO;
        for (AccAccount m : members) {
            if (!m.getAccountCode().equals(primary.getAccountCode())) {
                othersTotal = othersTotal.add(ledger.get(m.getAccountCode()));
            }
        }

        List<AccReconciliation> rows = new ArrayList<>(members.size());
        for (AccAccount m : members) {
            boolean isPrimary = m.getAccountCode().equals(primary.getAccountCode());
            rows.add(row(m.getAccountCode(), request.reconDate(),
                    isPrimary ? request.countedAmount().subtract(othersTotal)
                              : ledger.get(m.getAccountCode()),
                    ledger.get(m.getAccountCode()),
                    null, null,
                    request.note(), now, staffUserId));
        }
        // ⚠ FLUSHED. This is where uk_recon_account_date refuses a second close,
        // and it has to happen before anything is posted so the exception costs
        // nothing but a rollback.
        List<AccReconciliation> saved = reconciliations.saveAllAndFlush(rows);

        // --- Steps 3 and 4: the variance voucher, against the primary member. --
        Long varianceVoucherId = null;
        if (variance.signum() != 0) {
            varianceVoucherId = postVariance(
                    primary.getAccountCode(),
                    // ⚠ The counterpart is CASH_SAFE and not the till (E17). The
                    // evening sweep still moves the till's RECORDED balance, so
                    // the safe absorbs the difference and ends matching its own
                    // count.
                    CASH_SAFE,
                    variance, explanation, request.reconDate(), now,
                    key + ":VARIANCE", request.note(), staffUserId);

            AccReconciliation primaryRow = saved.stream()
                    .filter(r -> r.getAccountCode().equals(primary.getAccountCode()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "the primary member's reconciliation row was not written"));
            primaryRow.setVarianceVoucherId(varianceVoucherId);
            reconciliations.saveAndFlush(primaryRow);
        }

        // --- Step 5: the evening sweep. ---------------------------------------
        PoolCloseResponse.Sweep sweep = postSweep(members, request.reconDate(), now,
                key + ":SWEEP", staffUserId);

        return new PoolCloseResponse(
                poolCode, request.reconDate(), request.countedAmount(), expected, variance,
                varianceVoucherId, sweep,
                saved.stream().map(ReconciliationResponse::from)
                        .sorted(Comparator.comparing(ReconciliationResponse::accountCode))
                        .toList(),
                false);
    }

    // ==================================================================
    // Endpoint 3 — GET /accounts/{accountCode}/reconciliations
    // ==================================================================

    /** One account's close history, newest first. */
    @Transactional(readOnly = true)
    public Page<ReconciliationResponse> history(String accountCode, LocalDate from, LocalDate to,
                                                int page, int size) {
        requireKnownAccount(accountCode);
        PageRequest pageable = PageRequest.of(page, size);
        Page<AccReconciliation> rows = (from == null && to == null)
                ? reconciliations.findByAccountCodeOrderByReconDateDesc(accountCode, pageable)
                : reconciliations.findByAccountCodeAndReconDateBetweenOrderByReconDateDesc(
                        accountCode,
                        from == null ? LocalDate.of(1000, 1, 1) : from,
                        to == null ? LocalDate.of(9999, 12, 31) : to,
                        pageable);
        return rows.map(ReconciliationResponse::from);
    }

    // ==================================================================
    // Endpoint 4 — POST /accounts/{accountCode}/reconciliations
    // ==================================================================

    /**
     * Closes a single reconcilable account that is not in a pool, and — on a
     * float — corrects its balance.
     *
     * <p><b>No sweep</b>: there is no account above the safe to sweep into, and a
     * bank or a float is not emptied into anything.
     */
    @Transactional
    public ReconciliationResponse closeAccount(String accountCode, ReconciliationRequest request,
                                               Integer staffUserId) {
        AccAccount account = requireKnownAccount(accountCode);
        require(account.isReconcilable(),
                "account " + accountCode + " has no independent source to count against "
                        + "(IS_RECONCILABLE is false), so it cannot be reconciled");
        if (isPooled(account)) {
            throw new LedgerPostingException(
                    accountCode + " is a member of cash pool " + account.getCashPool()
                            + " and closes with it: POST /accounts/pools/" + account.getCashPool()
                            + "/closes (endpoint 2). A pool closes as a unit, from one counted "
                            + "figure for the whole drawer (A2, log 9.1).");
        }

        requireNotInFuture(request.reconDate());
        require(request.countedAmount().signum() >= 0,
                "countedAmount cannot be negative: " + request.countedAmount());

        boolean isFloat = account.getFloatPattern() != null;
        require(isFloat || request.faceCounted() == null,
                "faceCounted applies to a float account only; " + accountCode
                        + " has no FLOAT_PATTERN");

        BigDecimal ledger = derivedBalances(List.of(accountCode)).get(accountCode);
        BigDecimal variance = request.countedAmount().subtract(ledger);
        ResolvedExplanation explanation = resolveExplanation(request.explained(), variance, account);

        LocalDateTime now = LocalDateTime.now(clock);
        String key = accountReconKey(accountCode, request.reconDate());

        // The face pair is a float's SECOND, independent comparison (§3). At a
        // cost/face ratio of exactly 1.0 — which all four wallets are, log
        // §2.4:97-99 — faceCounted is the same figure the operator reads off the
        // portal, so it defaults to countedAmount rather than being typed twice.
        BigDecimal faceCounted = null;
        BigDecimal faceLedger = null;
        if (isFloat) {
            faceCounted = request.faceCounted() == null
                    ? request.countedAmount() : request.faceCounted();
            faceLedger = paise(balances.faceLedgerOf(accountCode));
        }

        // --- The row, BEFORE the voucher. Same reason as the pool close. ------
        AccReconciliation saved = reconciliations.saveAndFlush(row(
                accountCode, request.reconDate(), request.countedAmount(), ledger,
                faceCounted, faceLedger, request.note(), now, staffUserId));

        if (variance.signum() != 0) {
            // ⚠ The counterpart is the account being closed, not CASH_SAFE. The
            // pool close can use the safe because the sweep moves the till's
            // recorded balance anyway (E17); nothing sweeps here, so a variance
            // booked elsewhere would leave this account's count unreconciled
            // forever.
            Long varianceVoucherId = postVariance(accountCode, accountCode, variance, explanation,
                    request.reconDate(), now, key, request.note(), staffUserId);
            saved.setVarianceVoucherId(varianceVoucherId);
            saved = reconciliations.saveAndFlush(saved);
        }

        return ReconciliationResponse.from(saved);
    }

    // ==================================================================
    // The replay reads
    // ==================================================================

    /**
     * ⚠ A FRESH READ-ONLY TRANSACTION, and that is the whole point.
     * {@code REQUIRES_NEW} because the caller reaches here from a catch block
     * after its own transaction has rolled back — <b>including the
     * {@code acc_reconciliation} rows that caused the duplicate</b> — so the
     * original close is not visible from inside the aborted one (§1.3,
     * RechargeController.java:240-247).
     *
     * <p>A pre-check {@code SELECT} before inserting would be a race in itself:
     * two clicks can both read "not closed" before either writes.
     * Insert-then-recover is correct and needs no locking.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<PoolCloseResponse> replayedPoolClose(String poolCode, LocalDate reconDate) {
        List<AccAccount> members = accounts.findByCashPoolAndActiveTrueOrderBySortOrder(poolCode);
        if (members.isEmpty()) {
            return Optional.empty();
        }
        List<String> memberCodes = members.stream().map(AccAccount::getAccountCode).toList();
        List<AccReconciliation> rows = reconciliations
                .findByAccountCodeInAndReconDateOrderByAccountCode(memberCodes, reconDate);
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        BigDecimal counted = ZERO;
        BigDecimal expected = ZERO;
        Long varianceVoucherId = null;
        for (AccReconciliation r : rows) {
            counted = counted.add(r.getCountedAmount());
            expected = expected.add(r.getLedgerAmount());
            if (r.getVarianceVoucherId() != null) {
                varianceVoucherId = r.getVarianceVoucherId();
            }
        }

        String key = poolCloseKey(poolCode, reconDate);
        PoolCloseResponse.Sweep sweep = vouchers.findByIdempotencyKey(key + ":SWEEP")
                .map(v -> new PoolCloseResponse.Sweep(v.getVoucherId(), sweptAmount(v),
                        PoolCloseResponse.Sweep.SweepStatus.POSTED))
                .orElseGet(() -> new PoolCloseResponse.Sweep(null, ZERO,
                        PoolCloseResponse.Sweep.SweepStatus.SKIPPED_ALL_MEMBERS_ZERO));

        return Optional.of(new PoolCloseResponse(
                poolCode, reconDate, counted, expected, counted.subtract(expected),
                varianceVoucherId, sweep,
                rows.stream().map(ReconciliationResponse::from).toList(),
                true));
    }

    /** The single-account equivalent. Same transaction reasoning as above. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<ReconciliationResponse> replayedAccountClose(String accountCode,
                                                                 LocalDate reconDate) {
        return reconciliations.findByAccountCodeAndReconDate(accountCode, reconDate)
                .map(ReconciliationResponse::from);
    }

    // ==================================================================
    // The postings
    // ==================================================================

    /**
     * Steps 3 and 4 in one place, because they are the same voucher with
     * different legs and keeping them apart invited the two to drift.
     *
     * <p>Four shapes, all from design-ledger.md §11.5:
     *
     * <ul>
     *   <li><b>E17</b> explained {@code OUT}: {@code Dr EXP_<category> /
     *       Cr <counterpart>} as a {@code PAYMENT} — the real expense, not "Cash
     *       Short", because booking it to the variance account throws away the
     *       one fact worth keeping (log §9.1);
     *   <li><b>log §14.4</b> explained {@code IN}: {@code Dr <counterpart> /
     *       Cr INC_<income>} as a {@code RECEIPT} — the exact mirror, which the
     *       original design could not express at all;
     *   <li><b>E17b</b> unexplained short: {@code Dr EXP_CASH_SHORT /
     *       Cr <account>} as a {@code JOURNAL};
     *   <li><b>E17c</b> unexplained over: {@code Dr <account> / Cr INC_CASH_OVER}.
     * </ul>
     *
     * <p>⚠ The unexplained pair posts ALWAYS, however small. There is no
     * threshold and there must not be one (log §9.1): {@code EXP_CASH_SHORT} and
     * {@code INC_CASH_OVER} answer "how much can we not account for?", and a
     * threshold would make a genuine unexplained amount invisible.
     *
     * @param account      the account being reconciled — the pool's
     *                     {@code IS_POOL_PRIMARY} member, or the single account
     * @param counterpart  the other side of an EXPLAINED variance;
     *                     {@code CASH_SAFE} for a pool (E17), the account itself
     *                     for a single close
     */
    private Long postVariance(String account, String counterpart, BigDecimal variance,
                              ResolvedExplanation explanation, LocalDate reconDate,
                              LocalDateTime now, String idempotencyKey, String note,
                              Integer staffUserId) {
        BigDecimal amount = variance.abs();
        boolean over = variance.signum() > 0;

        String voucherType;
        List<VoucherLeg> legs;
        String narration;

        if (explanation == null) {
            voucherType = "JOURNAL";
            legs = over
                    ? List.of(VoucherLeg.debit(account, amount),
                              VoucherLeg.credit(INC_CASH_OVER, amount))
                    : List.of(VoucherLeg.debit(EXP_CASH_SHORT, amount),
                              VoucherLeg.credit(account, amount));
            narration = (over ? "Cash over " : "Cash short ") + amount
                    + " on " + reconDate + ", cause unknown";
        } else if (explanation.rateCorrection()) {
            // Dr FLOAT_X / Cr INC_RCHG_COMM. One journal repairs the commission
            // understatement and the float drift together, because both errors
            // are the same rupee amount (log §8.4:709-714) — which is itself the
            // check that the correction is right.
            voucherType = "JOURNAL";
            legs = List.of(VoucherLeg.debit(account, amount),
                           VoucherLeg.credit(INC_RCHG_COMM, amount));
            narration = "Float rate correction " + amount + " on " + reconDate;
        } else if (explanation.isIn()) {
            voucherType = "RECEIPT";
            legs = List.of(VoucherLeg.debit(counterpart, amount),
                           VoucherLeg.credit(explanation.accountCode(), amount));
            narration = "Cash over " + amount + " on " + reconDate + ": "
                    + explanation.accountCode();
        } else {
            voucherType = "PAYMENT";
            legs = List.of(VoucherLeg.debit(explanation.accountCode(), amount),
                           VoucherLeg.credit(counterpart, amount));
            narration = "Cash short " + amount + " on " + reconDate + ": "
                    + explanation.accountCode();
        }

        if (note != null && !note.isBlank()) {
            narration = narration + " — " + note;
        }

        return postings.post(new VoucherDraft(
                voucherType, reconDate, now, truncate(narration), SOURCE_CASH_RECON,
                "acc_reconciliation#" + account + ":" + reconDate,
                idempotencyKey, null, staffUserId, legs)).voucherId();
    }

    /**
     * E18, the evening sweep: one {@code CONTRA} debiting {@code CASH_SAFE} by
     * the members' recorded balances and crediting each member, leaving every
     * till at zero (log §3).
     *
     * <p>⚠ <b>THE BALANCES ARE RE-READ HERE, AFTER THE VARIANCE VOUCHER.</b> That
     * is not belt-and-braces, it is the difference between a correct sweep and a
     * wrong one, and the two variance shapes are why: an UNEXPLAINED journal
     * moves the till itself (E17b/E17c), so the till now records the counted
     * figure, while an EXPLAINED one hits {@code CASH_SAFE} and leaves the till's
     * recorded balance alone (E17). Re-reading gets both right without a rule;
     * arithmetic on the pre-close balances would need to know which case it was
     * in.
     *
     * <p>⚠ <b>A ZERO LEG IS ILLEGAL</b> (§1.1, added 2026-11-11, and it was a
     * defect rather than a preference). Step 5 read "crediting each member", and
     * {@code acc_voucher_line} carries
     * {@code ck_line_nonzero CHECK (DR_AMOUNT + CR_AMOUNT > 0)} — so on a day
     * where one member took nothing, the literal implementation posts
     * {@code Cr CASH_TICKET 0.00} and <b>the whole close fails on a constraint
     * violation.</b> Not a junk voucher: a failed close. Bookings are occasional,
     * so a day with recharge takings and no ticket is the ORDINARY case.
     *
     * <p>So the sweep credits only members with a non-zero recorded balance, and
     * posts nothing at all when every member is zero. The
     * {@code acc_reconciliation} rows are written either way — they are the
     * evidence the count happened, and a day that genuinely took nothing still
     * deserves a recorded count.
     */
    private PoolCloseResponse.Sweep postSweep(List<AccAccount> members, LocalDate reconDate,
                                              LocalDateTime now, String idempotencyKey,
                                              Integer staffUserId) {
        List<String> memberCodes = members.stream().map(AccAccount::getAccountCode).toList();
        Map<String, BigDecimal> recorded = derivedBalances(memberCodes);

        List<VoucherLeg> memberLegs = new ArrayList<>(members.size());
        BigDecimal net = ZERO;
        for (String code : memberCodes) {
            BigDecimal balance = recorded.get(code);
            if (balance.signum() == 0) {
                continue;
            }
            // ⚠ A NEGATIVE MEMBER IS DEBITED, NOT REFUSED, and this was a real
            // defect found by setting up the real close rather than by the tests
            // above. "Zero every member" is the instruction (log §3); for a
            // positive balance that means crediting it, and for a negative one it
            // means DEBITING it. An earlier version refused a negative member as
            // an upstream posting error — which it is, BEFORE the close, and that
            // check now lives at step 2 where it belongs.
            //
            // After step 4 a negative member is ordinary arithmetic, not a fault.
            // A2 lands the pool's whole variance on the IS_POOL_PRIMARY member,
            // so any day the shortfall exceeds that member's OWN takings leaves
            // it negative: two cash bookings and nothing else gives
            // CASH_TICKET 970.00, CASH_RECHARGE 0.00, and a count of 960.00 puts
            // CASH_RECHARGE at -10.00. Refusing there would make a perfectly
            // ordinary evening impossible to close.
            memberLegs.add(balance.signum() > 0
                    ? VoucherLeg.credit(code, balance)
                    : VoucherLeg.debit(code, balance.negate()));
            net = net.add(balance);
        }

        if (memberLegs.isEmpty()) {
            return new PoolCloseResponse.Sweep(null, ZERO,
                    PoolCloseResponse.Sweep.SweepStatus.SKIPPED_ALL_MEMBERS_ZERO);
        }

        // The net cannot be negative: it equals the counted figure on an
        // unexplained variance and the expected figure on an explained one, and
        // both are refused below zero. Asserted rather than assumed, because a
        // Cr CASH_SAFE here would be the safe handing money back to the drawer.
        require(net.signum() >= 0,
                "the sweep would take " + net + " out of " + CASH_SAFE
                        + ", which is not what an evening sweep does");

        List<VoucherLeg> legs = new ArrayList<>(memberLegs.size() + 1);
        // ⚠ Omitted when the net is zero — ck_line_nonzero refuses a
        // Dr CASH_SAFE 0.00 exactly as it refuses a zero credit. Members that
        // cancel out (+10.00 and -10.00) still have to be zeroed, and they
        // balance against each other with no safe leg at all.
        if (net.signum() > 0) {
            legs.add(VoucherLeg.debit(CASH_SAFE, net));
        }
        legs.addAll(memberLegs);

        long voucherId = postings.post(new VoucherDraft(
                "CONTRA", reconDate, now,
                truncate("Evening sweep to Cash-Safe, " + reconDate),
                SOURCE_CASH_RECON, "acc_reconciliation#" + reconDate,
                idempotencyKey, null, staffUserId, legs)).voucherId();

        return new PoolCloseResponse.Sweep(voucherId, net,
                PoolCloseResponse.Sweep.SweepStatus.POSTED);
    }

    // ==================================================================
    // Validation
    // ==================================================================

    /**
     * §1.1's 422 list for the {@code explained} block, and the resolution of
     * which account it names.
     *
     * @param account the account being closed, or null for a pool close — only a
     *                float close may carry a {@code reason}
     * @return null when there is nothing to explain, which is the unexplained
     *         path
     */
    private ResolvedExplanation resolveExplanation(ExplainedVariance explained,
                                                   BigDecimal variance,
                                                   AccAccount account) {
        if (explained == null) {
            return null;
        }
        require(variance.signum() != 0,
                "the count matches exactly, so there is no variance to explain. Omit the "
                        + "explained block.");
        require(explained.amount().signum() > 0,
                "explained.amount must be positive; the direction carries the sign");
        require(explained.amount().compareTo(variance.abs()) == 0,
                "explained.amount (" + explained.amount() + ") must equal the whole variance ("
                        + variance.abs() + "). A partial explanation is refused rather than "
                        + "split, so the remainder can never land in EXP_CASH_SHORT unnoticed.");

        boolean over = variance.signum() > 0;
        require(over == explained.isIn(),
                "explained.direction " + explained.direction() + " disagrees with the variance: "
                        + "the drawer is " + (over ? "OVER" : "SHORT") + " by "
                        + variance.abs() + ", so the direction is " + (over ? "IN" : "OUT"));

        boolean named = explained.accountCode() != null && !explained.accountCode().isBlank();
        boolean reasoned = explained.reason() != null;
        require(named != reasoned,
                "an explained variance names exactly one of accountCode or reason, never both "
                        + "and never neither");

        if (reasoned) {
            // RATE_CORRECTION is the only reason, and its accounts are fixed
            // precisely because INC_RCHG_COMM is ALLOW_MANUAL = FALSE and could
            // never be named through accountCode (A7, log §14.9).
            require(account != null && account.getFloatPattern() != null,
                    "reason " + explained.reason() + " applies to a float account only, through "
                            + "endpoint 4. A float correction never posts from endpoint 9 "
                            + "(log 14.9).");
            // ⚠ AND ONLY ON AN OVER. The legs are fixed at Dr FLOAT_X /
            // Cr INC_RCHG_COMM, which RAISES the float — right when the portal
            // shows more than the books, which is what a provisional rate
            // understating commission produces (log §8.4:709-714). The direction
            // check above only tests agreement with the sign, so without this a
            // RATE_CORRECTION sent as OUT on a short would pass it and then post
            // legs that move the float further the wrong way.
            require(explained.isIn(),
                    "reason " + explained.reason() + " posts Dr " + account.getAccountCode()
                            + " / Cr " + INC_RCHG_COMM + ", which raises the float, so it "
                            + "applies to an OVER (direction IN) only. The drawer here is SHORT "
                            + "by " + variance.abs() + "; a float recorded above its portal "
                            + "balance is a different fault and needs its own correction.");
            return new ResolvedExplanation(null, true, true);
        }

        AccAccount target = accounts.findById(explained.accountCode()).orElseThrow(
                () -> new LedgerPostingException("no such account: " + explained.accountCode()));
        require(target.isActive(), "account is not active: " + target.getAccountCode());
        String wanted = explained.isIn() ? "INCOME" : "EXPENSE";
        require(wanted.equals(target.getAccountType()),
                "a variance explained as " + explained.direction() + " needs an " + wanted
                        + " account; " + target.getAccountCode() + " is "
                        + target.getAccountType());
        require(target.isAllowManual(),
                target.getAccountCode() + " is ALLOW_MANUAL = FALSE and cannot be named by hand "
                        + "(A7). EXP_CASH_SHORT and INC_CASH_OVER are posted by the close itself, "
                        + "for the genuinely unexplained — omit the explained block instead.");

        return new ResolvedExplanation(target.getAccountCode(), explained.isIn(), false);
    }

    /**
     * ⚠ A close dated in the future would seal vouchers that do not exist yet.
     * The lock test is {@code RECON_DATE >= VOUCHER_DATE}, so a row dated next
     * Friday locks every voucher written between now and then against
     * delete-and-repost, silently, with no count behind it (§1.1).
     */
    private void requireNotInFuture(LocalDate reconDate) {
        LocalDate today = LocalDate.now(clock);
        require(!reconDate.isAfter(today),
                "reconDate " + reconDate + " is in the future (today is " + today + "). A close "
                        + "seals every voucher dated on or before it, so a forward-dated one "
                        + "would seal entries nobody has counted.");
    }

    private AccAccount solePrimaryOf(String poolCode, List<AccAccount> members) {
        List<AccAccount> primaries = members.stream().filter(AccAccount::isPoolPrimary).toList();
        // "Exactly one member per pool carries IS_POOL_PRIMARY" is a cross-row
        // rule MySQL CHECK cannot express, so it is a service check plus a V27
        // assertion (V21:283-286). This is that service check.
        require(primaries.size() == 1,
                "cash pool " + poolCode + " has " + primaries.size() + " IS_POOL_PRIMARY members "
                        + "and needs exactly one; it is the member the whole variance lands on "
                        + "(log 9.1).");
        return primaries.get(0);
    }

    private AccAccount requireKnownAccount(String accountCode) {
        return accounts.findById(accountCode).orElseThrow(
                () -> new LedgerPostingException("no such account: " + accountCode));
    }

    // ==================================================================
    // Plumbing
    // ==================================================================

    /** {@code SAFE} is not a pool here. See {@link #POOL_SAFE}. */
    private static boolean isPooled(AccAccount account) {
        return account.getCashPool() != null && !POOL_SAFE.equals(account.getCashPool());
    }

    static String poolCloseKey(String poolCode, LocalDate reconDate) {
        return "POOL_CLOSE:" + poolCode + ":" + reconDate;
    }

    static String accountReconKey(String accountCode, LocalDate reconDate) {
        return "ACCT_RECON:" + accountCode + ":" + reconDate;
    }

    /**
     * Derived balances, straight off {@code acc_account_balance} (V26).
     *
     * <p>Every code asked for comes back, at {@code 0.00} when the view has no
     * row — so callers can index the map without a null check and an account
     * with no postings reads as empty rather than as missing.
     */
    private Map<String, BigDecimal> derivedBalances(Collection<String> codes) {
        Map<String, BigDecimal> out = new HashMap<>();
        for (String code : codes) {
            out.put(code, ZERO);
        }
        for (Object[] row : balances.balancesOf(codes)) {
            out.put((String) row[0], paise((BigDecimal) row[1]));
        }
        return out;
    }

    /**
     * ⚠ NORMALISED TO SCALE 2 ON THE WAY OUT OF SQL, because
     * {@code COALESCE(SUM(...), 0)} returns a scale-ZERO zero when an account has
     * no postings — the literal {@code 0} is an integer and MySQL does not widen
     * it to the column's {@code decimal(38,2)}.
     *
     * <p>That matters twice. {@code BigDecimal.equals} is scale-sensitive, so
     * {@code 0} and {@code 0.00} are different objects and an assertion on either
     * fails against the other. And these figures are written straight onto
     * {@code acc_reconciliation}, where Hibernate keeps the in-memory scale after
     * the flush — so a day that took nothing would answer {@code "ledgerAmount":
     * 0} while every other day answered {@code 0.00}, from the same field.
     *
     * <p>{@code UNNECESSARY} rather than a rounding mode, deliberately, and for
     * the reason {@code settlement.Amounts} gives: money finer than a paisa is a
     * programming error, not something to round away.
     */
    private static BigDecimal paise(BigDecimal amount) {
        return amount == null ? ZERO : amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private Map<String, AccReconciliation> newestCloses(Collection<String> codes) {
        Map<String, AccReconciliation> out = new HashMap<>();
        if (codes.isEmpty()) {
            return out;
        }
        for (AccReconciliation r : reconciliations.findNewestPerAccount(codes)) {
            out.put(r.getAccountCode(), r);
        }
        return out;
    }

    private BigDecimal sweptAmount(AccVoucher sweep) {
        BigDecimal total = ZERO;
        for (AccVoucherLine line : voucherLines.findByVoucherIdOrderByLineNo(sweep.getVoucherId())) {
            if (CASH_SAFE.equals(line.getAccountCode())) {
                total = total.add(line.getDrAmount());
            }
        }
        return total;
    }

    private static AccReconciliation row(String accountCode, LocalDate reconDate,
                                         BigDecimal counted, BigDecimal ledger,
                                         BigDecimal faceCounted, BigDecimal faceLedger,
                                         String note, LocalDateTime closedAt,
                                         Integer staffUserId) {
        AccReconciliation r = new AccReconciliation();
        r.setAccountCode(accountCode);
        r.setReconDate(reconDate);
        r.setCountedAmount(counted);
        r.setLedgerAmount(ledger);
        r.setFaceCounted(faceCounted);
        r.setFaceLedger(faceLedger);
        r.setNote(note);
        r.setClosedAt(closedAt);
        r.setClosedBy(staffUserId);
        return r;
    }

    /** {@code NARRATION} is {@code VARCHAR(255)}; a note can fill it on its own. */
    private static String truncate(String narration) {
        return narration.length() <= 255 ? narration : narration.substring(0, 255);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new LedgerPostingException(message);
        }
    }

    /**
     * What survived {@link #resolveExplanation}: an account code OR the one named
     * reason, never both.
     */
    private record ResolvedExplanation(String accountCode, boolean isIn, boolean rateCorrection) {
    }
}
