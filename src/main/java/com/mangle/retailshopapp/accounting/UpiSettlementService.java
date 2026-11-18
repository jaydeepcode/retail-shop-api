package com.mangle.retailshopapp.accounting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.mangle.retailshopapp.accounting.model.AccAccount;
import com.mangle.retailshopapp.accounting.model.AccReconciliation;
import com.mangle.retailshopapp.accounting.model.AccVoucher;
import com.mangle.retailshopapp.accounting.model.AccVoucherLine;
import com.mangle.retailshopapp.accounting.model.request.UpiSettlementRequest;
import com.mangle.retailshopapp.accounting.model.response.ReconciliationResponse;
import com.mangle.retailshopapp.accounting.model.response.UpiPendingResponse;
import com.mangle.retailshopapp.accounting.model.response.UpiSettlementResponse;
import com.mangle.retailshopapp.accounting.repo.AccAccountRepository;
import com.mangle.retailshopapp.accounting.repo.AccReconciliationRepository;
import com.mangle.retailshopapp.accounting.repo.AccVoucherLineRepository;
import com.mangle.retailshopapp.accounting.repo.AccVoucherRepository;
import com.mangle.retailshopapp.accounting.repo.UpiClearingRepository;
import com.mangle.retailshopapp.settings.SettingService;

/**
 * UPI settlement — endpoints 5 and 6 of design-ledger-api.md §1.2.
 *
 * <h2>A4: the amount that arrived, and more than once per collection date</h2>
 *
 * The bank credits what it credits. ₹3,450 collected and ₹3,200 landing is
 * E16b's partial settlement: the ₹250 stays in {@code UPI_CLEARING} and ages on
 * endpoint 5 until it arrives. <b>Nothing is written off automatically and no
 * write-off account exists</b> — a settlement has never once mismatched in seven
 * years (log §9.2), so the machinery for it would be untested code guarding an
 * event that has never happened, and an automatic write-off would make the first
 * real mismatch invisible.
 *
 * <h2>⚠ Where the sequence lives, and why it is not the idempotency key</h2>
 *
 * <b>Settled with the owner on 2026-11-16, and it resolves a contradiction
 * between two sections of the design.</b> §1.2's table requires an
 * {@code Idempotency-Key} header on this endpoint; §1.3's table says #6's key is
 * {@code UPI_SETTLEMENT:<collectionDate>:<n>}, <i>server</i>-derived, and that
 * every key is stored on {@code acc_voucher.IDEMPOTENCY_KEY}. Both cannot hold:
 * {@code uk_vch_idem} is a single column.
 *
 * <p>And taking §1.3 literally has a hole. If the server derives {@code n}, then
 * a retry — the response lost on the shop LAN, the staff member pressing again —
 * derives {@code n + 1} and records a SECOND settlement. The bank credited ₹3,200
 * once and the books would say ₹6,400, found at the next reconciliation or not at
 * all. An idempotency key exists to close exactly that, so a derived one closes
 * nothing.
 *
 * <p>So: <b>the client's opaque key is stored on
 * {@code acc_voucher.IDEMPOTENCY_KEY}</b>, where {@code uk_vch_idem} makes a
 * retry a 409 carrying the original — identical to endpoints 7, 8, 9 and 19 — and
 * <b>the server-derived {@code UPI_SETTLEMENT:<collectionDate>:<n>} is published
 * on {@code acc_voucher.SOURCE_REF}</b>, where it still identifies the settlement
 * and still makes a partial one expressible, which §1.2 says is the whole reason
 * the sequence exists. §1.3's table carries the correction.
 *
 * <h2>⚠ The reconciliation row accumulates, and is verified before it does</h2>
 *
 * §2 requires an {@code acc_reconciliation} row on {@code UPI_CLEARING} in the
 * same transaction — "the reconciliation row is the evidence for the voucher's
 * amount". {@code uk_recon_account_date (ACCOUNT_CODE, RECON_DATE)} allows one row
 * per date, and A4 allows several settlements per date, so <b>the second
 * settlement extends the row rather than inserting one</b>:
 * {@code COUNTED_AMOUNT} becomes everything credited against that collection date
 * and {@code LEDGER_AMOUNT} what was collected, which makes the generated
 * {@code VARIANCE_AMOUNT} the negated residue — zero exactly when the date is
 * fully settled.
 *
 * <p>⚠ <b>{@code VARIANCE_VOUCHER_ID} STAYS NULL ON THESE ROWS, AND THAT IS NOT
 * AN OMISSION.</b> On a cash close that column points at the voucher that booked
 * the variance; here the variance is money in transit and <b>nothing books it</b>
 * (§1.2). A row with a non-zero variance and no voucher is precisely what "still
 * owed by the bank" looks like.
 *
 * <p>⚠ <b>And the row's PROVENANCE is checked three ways before it is
 * extended</b>, because endpoint 4 can also write a row for this account and
 * date — {@code UPI_CLEARING} is {@code IS_RECONCILABLE = TRUE} and in no pool,
 * so it is closeable there. Accumulating onto a figure a close wrote would
 * destroy the evidence that close exists to hold. The vouchers are append-only
 * and are the authority; the row is the evidence. {@link #upsertRow} has the
 * three checks and why each was needed — <b>each one was added because the
 * previous set let a real case through</b>, and the narrowest of them, "has any
 * settlement happened for this date at all", is the one that catches a close of
 * a quiet day: zero counted, zero ledger, and no variance voucher, because
 * there was no difference to book.
 *
 * <p>⚠ <b>ONE CASE REMAINS OPEN AND IS DOCUMENTED RATHER THAN CHASED</b>
 * (design-ledger-api.md §1.2): a close whose counted figure happens to equal a
 * NON-zero settled total for that date still passes all three. It is narrower
 * again and the same shape, and an exact answer needs
 * {@code acc_reconciliation} to record which endpoint wrote the row — a column,
 * and therefore a migration, which is S6's.
 */
@Service
public class UpiSettlementService {

    static final String UPI_CLEARING = UpiClearingRepository.UPI_CLEARING;
    static final String SOURCE_UPI_SETTLEMENT = UpiClearingRepository.SOURCE_UPI_SETTLEMENT;

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final AccAccountRepository accounts;
    private final AccReconciliationRepository reconciliations;
    private final AccVoucherRepository vouchers;
    private final AccVoucherLineRepository voucherLines;
    private final UpiClearingRepository clearing;
    private final LedgerPostingService postings;
    private final SettingService settings;
    private final Clock clock;

    public UpiSettlementService(AccAccountRepository accounts,
                                AccReconciliationRepository reconciliations,
                                AccVoucherRepository vouchers,
                                AccVoucherLineRepository voucherLines,
                                UpiClearingRepository clearing,
                                LedgerPostingService postings,
                                SettingService settings,
                                Clock clock) {
        this.accounts = accounts;
        this.reconciliations = reconciliations;
        this.vouchers = vouchers;
        this.voucherLines = voucherLines;
        this.clearing = clearing;
        this.postings = postings;
        this.settings = settings;
        this.clock = clock;
    }

    // ==================================================================
    // Endpoint 5 — GET /accounts/upi-clearing/pending
    // ==================================================================

    /**
     * What is still in transit, by collection date, aged against {@code asOf}.
     *
     * <p>Collections come from the vouchers and settlements from the
     * {@code acc_reconciliation} rows — one indexed read each, rather than
     * matching every settlement voucher's {@code SOURCE_REF} prefix in SQL. The
     * row is exactly what §2 made it: the per-date evidence. Endpoint 6 is where
     * the two are cross-checked, so a divergence is caught when a settlement is
     * written rather than quietly reported here.
     *
     * @param asOf null for today
     */
    @Transactional(readOnly = true)
    public UpiPendingResponse pending(LocalDate asOf) {
        LocalDate on = asOf == null ? LocalDate.now(clock) : asOf;

        Map<LocalDate, BigDecimal> settled = new HashMap<>();
        for (AccReconciliation r : reconciliations
                .findByAccountCodeAndReconDateLessThanEqualOrderByReconDate(UPI_CLEARING, on)) {
            settled.put(r.getReconDate(), paise(r.getCountedAmount()));
        }

        List<UpiPendingResponse.PendingDate> rows = new ArrayList<>();
        BigDecimal total = ZERO;
        for (Object[] row : clearing.collectedPerDate(on)) {
            LocalDate collectionDate = toLocalDate(row[0]);
            BigDecimal collected = paise((BigDecimal) row[1]);
            BigDecimal settledSoFar = settled.getOrDefault(collectionDate, ZERO);
            BigDecimal outstanding = collected.subtract(settledSoFar);

            // A fully settled date is not in transit and has no business on a
            // screen about what is. A NON-ZERO one stays, including a negative —
            // see PendingDate.pendingAmount.
            if (outstanding.signum() == 0) {
                continue;
            }
            rows.add(new UpiPendingResponse.PendingDate(
                    collectionDate, collected, settledSoFar, outstanding,
                    ChronoUnit.DAYS.between(collectionDate, on)));
            total = total.add(outstanding);
        }

        return new UpiPendingResponse(on, total, rows);
    }

    // ==================================================================
    // Endpoint 6 — POST /accounts/upi-clearing/settlements
    // ==================================================================

    /**
     * Records what the bank credited: {@code Dr <bank> / Cr UPI_CLEARING}
     * (E16/E16b), plus the reconciliation row, in one transaction.
     *
     * @throws LedgerPostingException on §1.2's 422 conditions
     */
    @Transactional
    public UpiSettlementResponse settle(UpiSettlementRequest request, String idempotencyKey,
                                        Integer staffUserId) {
        // ⚠ FIRST, AND BEFORE THE RECONCILIATION ROW IS TOUCHED. A replay noticed
        // only inside LedgerPostingService.post would already have had
        // COUNTED_AMOUNT incremented a second time by the block below — the
        // voucher would be refused and the row would be wrong.
        Optional<UpiSettlementResponse> replay = replayedInThisTransaction(idempotencyKey);
        if (replay.isPresent()) {
            return replay.get();
        }

        LocalDate today = LocalDate.now(clock);
        BigDecimal settledAmount = paise(request.settledAmount());
        require(settledAmount.signum() > 0,
                "settledAmount must be positive; " + settledAmount + " was sent. A bank credit "
                        + "of nothing is not a settlement, and money going the other way is a "
                        + "reversal this endpoint does not record.");
        require(!request.settledOn().isBefore(request.collectionDate()),
                "settledOn " + request.settledOn() + " is before collectionDate "
                        + request.collectionDate() + ". Money cannot land before it was taken.");
        require(!request.collectionDate().isAfter(today),
                "collectionDate " + request.collectionDate() + " is in the future (today is "
                        + today + ").");
        require(!request.settledOn().isAfter(today),
                "settledOn " + request.settledOn() + " is in the future (today is " + today
                        + "). Record the settlement on the day it lands.");

        AccAccount bank = resolveBank();

        BigDecimal collected = paise(clearing.collectedOn(request.collectionDate()));
        String refPrefix = sequencePrefix(request.collectionDate());
        BigDecimal settledSoFar = paise(clearing.settledAgainst(refPrefix + "%"));

        require(collected.signum() > 0,
                "nothing was collected by UPI on " + request.collectionDate()
                        + ", so there is nothing to settle against it. Check the collection date: "
                        + "it is the day the money was taken from customers, not the day it "
                        + "landed.");

        BigDecimal stillPending = collected.subtract(settledSoFar);
        require(settledAmount.compareTo(stillPending) <= 0,
                "settledAmount " + settledAmount + " is more than the " + stillPending
                        + " still pending for " + request.collectionDate() + " (" + collected
                        + " collected, " + settledSoFar + " already settled). A credit larger "
                        + "than the collection is not a settlement of it — if the bank credited "
                        + "more than was taken, that is a separate receipt and needs a journal.");

        LocalDateTime now = LocalDateTime.now(clock);
        BigDecimal settledToDate = settledSoFar.add(settledAmount);

        // --- The row, BEFORE the voucher, as every other write here does. -----
        AccReconciliation row = upsertRow(request, collected, settledSoFar, settledToDate,
                now, staffUserId);

        // --- E16/E16b: Dr <bank> / Cr UPI_CLEARING. ---------------------------
        String sequence = refPrefix + (clearing.countSettlements(refPrefix + "%") + 1);
        String narration = "UPI settlement " + settledAmount + " for "
                + request.collectionDate() + " into " + bank.getDisplayName();
        if (request.note() != null && !request.note().isBlank()) {
            narration = narration + " — " + request.note();
        }

        long voucherId = postings.post(new VoucherDraft(
                "CONTRA", request.settledOn(), now, truncate(narration),
                SOURCE_UPI_SETTLEMENT, sequence, idempotencyKey, null, staffUserId,
                List.of(VoucherLeg.debit(bank.getAccountCode(), settledAmount),
                        VoucherLeg.credit(UPI_CLEARING, settledAmount)
                                .withPaymentMethod("UPI")))).voucherId();

        return new UpiSettlementResponse(
                request.collectionDate(), request.settledOn(), settledAmount, collected,
                settledToDate, collected.subtract(settledToDate), sequence,
                bank.getAccountCode(), voucherId,
                ReconciliationResponse.from(row), false);
    }

    /**
     * Writes the collection date's reconciliation row, or extends it.
     *
     * <p>⚠ <b>THE CROSS-CHECK IS THE REASON THIS IS NOT A ONE-LINE UPSERT.</b> An
     * existing row's {@code COUNTED_AMOUNT} must equal what the settlement
     * vouchers say has been credited. If it does not, the row was written by
     * something else — endpoint 4 can close {@code UPI_CLEARING} for any date,
     * since it is reconcilable and in no pool — and adding to it would leave one
     * row claiming to be two different kinds of count. The vouchers are
     * append-only and win; this refuses and names the other endpoint.
     *
     * <p>{@code LEDGER_AMOUNT} is re-stated on every settlement rather than
     * written once, because the collected figure can legitimately GROW after a
     * partial settlement: a forgotten UPI sale dated that day, entered later,
     * belongs to that collection date. A stale {@code LEDGER_AMOUNT} would make
     * the residue read low and hide money the bank still owes.
     */
    private AccReconciliation upsertRow(UpiSettlementRequest request, BigDecimal collected,
                                        BigDecimal settledSoFar, BigDecimal settledToDate,
                                        LocalDateTime now, Integer staffUserId) {
        Optional<AccReconciliation> existing =
                reconciliations.findByAccountCodeAndReconDate(UPI_CLEARING,
                        request.collectionDate());

        if (existing.isEmpty()) {
            AccReconciliation row = new AccReconciliation();
            row.setAccountCode(UPI_CLEARING);
            row.setReconDate(request.collectionDate());
            row.setCountedAmount(settledToDate);
            row.setLedgerAmount(collected);
            row.setNote(request.note());
            row.setClosedAt(now);
            row.setClosedBy(staffUserId);
            // ⚠ FLUSHED, so uk_recon_account_date refuses a concurrent first
            // settlement here rather than halfway through posting a voucher.
            return reconciliations.saveAndFlush(row);
        }

        AccReconciliation row = existing.get();

        // ⚠ THREE CHECKS, AND EACH ONE EXISTS BECAUSE THE PREVIOUS SET LET A
        // REAL CASE THROUGH. The row being extended must have been written by a
        // settlement; endpoint 4 can write one for this same account and date,
        // because UPI_CLEARING is IS_RECONCILABLE = TRUE and in no pool, and
        // accumulating onto a close's figures destroys the evidence that close
        // was written to hold.
        //
        // 1. A SETTLEMENT HAS HAPPENED FOR THIS DATE AT ALL. The narrowest
        //    statement of the rule and the one that catches the quiet day: a
        //    close on a date when the account was empty writes COUNTED_AMOUNT
        //    0.00, LEDGER_AMOUNT 0.00 and — having found no difference — NO
        //    variance voucher, so checks 2 and 3 both pass it. Reproduced
        //    against a real database: collections dated that day posted late,
        //    then a settlement, and the row silently became 100.00 / 3450.00.
        //
        //    It is sound because a settlement's row cannot exist unless a
        //    settlement already happened for that date — the FIRST settlement
        //    takes the isEmpty() branch above and inserts, and settledAmount <= 0
        //    is already a 422 — so an existing row with nothing settled against
        //    its date was written by something else. And it cannot break a call
        //    that works today: every settlement that reaches this line is a
        //    later one, which has settledSoFar > 0 by construction.
        require(settledSoFar.signum() > 0,
                "UPI_CLEARING already has a reconciliation row for " + request.collectionDate()
                        + " but no settlement has ever been recorded against that date, so a "
                        + "settlement did not write it — a close (endpoint 4) did. Extending it "
                        + "would overwrite the evidence of that close.");

        // 2. THE CLOSE BOOKED A DIFFERENCE. VARIANCE_VOUCHER_ID is the tell: a
        //    settlement NEVER sets it, because money in transit is not booked
        //    (§1.2), while a close that found any difference always does.
        require(row.getVarianceVoucherId() == null,
                "UPI_CLEARING's reconciliation row for " + request.collectionDate()
                        + " carries variance voucher " + row.getVarianceVoucherId()
                        + ", so it was written by a close (endpoint 4) that BOOKED that "
                        + "difference — a settlement never books one. Extending it would "
                        + "overwrite the evidence of that close. If the residue was written off "
                        + "in error, correct it with a JOURNAL against the expense account the "
                        + "close named.");

        // 3. THE FIGURES AGREE with what the settlement vouchers say — the
        //    general check, and the authority is the vouchers, which are
        //    append-only once sealed.
        require(paise(row.getCountedAmount()).compareTo(settledSoFar) == 0,
                "UPI_CLEARING already has a reconciliation row for " + request.collectionDate()
                        + " recording " + row.getCountedAmount() + " counted, but the settlement "
                        + "vouchers for that date total " + settledSoFar + ". That row was "
                        + "written by something other than a settlement — closing UPI_CLEARING "
                        + "through POST /accounts/UPI_CLEARING/reconciliations (endpoint 4) does "
                        + "exactly this — and a settlement will not add to a figure it does not "
                        + "own. Resolve the two before settling this date.");

        row.setCountedAmount(settledToDate);
        row.setLedgerAmount(collected);
        if (request.note() != null && !request.note().isBlank()) {
            // The newest note wins: it describes the current state of the date,
            // which is what a part-settled row's note is for ("₹250 short, not
            // yet arrived" stops being true when the ₹250 arrives).
            row.setNote(request.note());
        }
        row.setClosedAt(now);
        row.setClosedBy(staffUserId);
        return reconciliations.saveAndFlush(row);
    }

    // ==================================================================
    // The replay reads
    // ==================================================================

    private Optional<UpiSettlementResponse> replayedInThisTransaction(String idempotencyKey) {
        return vouchers.findByIdempotencyKey(idempotencyKey).map(this::replayOf);
    }

    /**
     * ⚠ A FRESH READ-ONLY TRANSACTION, for the controller's duplicate-key catch:
     * the race between two concurrent first-posts, where the winner's rows are
     * invisible from inside the loser's rolled-back transaction. An ordinary
     * retry never reaches it, because {@link #settle} looks the key up first.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<UpiSettlementResponse> replayed(String idempotencyKey) {
        return vouchers.findByIdempotencyKey(idempotencyKey).map(this::replayOf);
    }

    /**
     * Rebuilds a settlement from what is on file.
     *
     * <p>⚠ THE COLLECTION DATE COMES FROM THE SEQUENCE ON {@code SOURCE_REF},
     * which is the one place it is recorded on the voucher — the voucher's own
     * date is {@code settledOn}. That is a consequence of where the sequence
     * lives, and it is why {@link #collectionDateOf} is strict about the format
     * rather than lenient: a {@code SOURCE_REF} this cannot parse means a
     * settlement voucher was written by something that did not follow §1.2, and
     * guessing would report a date nobody recorded.
     */
    private UpiSettlementResponse replayOf(AccVoucher voucher) {
        LocalDate collectionDate = collectionDateOf(voucher);
        BigDecimal collected = paise(clearing.collectedOn(collectionDate));
        BigDecimal settledToDate =
                paise(clearing.settledAgainst(sequencePrefix(collectionDate) + "%"));
        BigDecimal thisOne = paise(clearing.settledAgainst(voucher.getSourceRef()));

        AccReconciliation row = reconciliations
                .findByAccountCodeAndReconDate(UPI_CLEARING, collectionDate)
                .orElseThrow(() -> new IllegalStateException(
                        "settlement voucher " + voucher.getVoucherId() + " has no "
                                + "acc_reconciliation row for " + collectionDate
                                + ", which cannot happen: the two are written in one "
                                + "transaction"));

        return new UpiSettlementResponse(
                collectionDate, voucher.getVoucherDate(), thisOne, collected, settledToDate,
                collected.subtract(settledToDate), voucher.getSourceRef(),
                bankOf(voucher), voucher.getVoucherId(),
                ReconciliationResponse.from(row), true);
    }

    /**
     * The bank leg of a settlement, read off the voucher rather than re-resolved
     * from the setting.
     *
     * <p>⚠ <b>A REPLAY MUST ANSWER WHERE THE MONEY ACTUALLY WENT.</b>
     * {@code bank.default.account} is configuration and can be edited between a
     * settlement and its retry — a second bank account is "configuration rather
     * than a code change" by design (V9:210-214) — so re-reading the setting
     * would report a bank this voucher never touched.
     *
     * <p>The debit leg IS the bank, by construction: a settlement has exactly two
     * legs and the other is the {@code UPI_CLEARING} credit.
     */
    private String bankOf(AccVoucher voucher) {
        return voucherLines.findByVoucherIdOrderByLineNo(voucher.getVoucherId()).stream()
                .filter(line -> line.getDrAmount() != null && line.getDrAmount().signum() > 0)
                .map(AccVoucherLine::getAccountCode)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "settlement voucher " + voucher.getVoucherId() + " has no debit leg, "
                                + "which V23 trigger 1 cannot have sealed"));
    }

    // ==================================================================
    // Validation and plumbing
    // ==================================================================

    /**
     * {@code rc_setting['bank.default.account']}, never a constant (log §9.3).
     *
     * <p>⚠ <b>A MISSING ROW IS A 500 AND MUST BE.</b>
     * {@link SettingService#requireString} throws {@code IllegalStateException}
     * for this key alone, deliberately: every other setting degrades to a
     * sensible default, and this one cannot, because the default would be a
     * GUESS about which bank account the shop's money moves through. The request
     * is not wrong, so a 422 would be a lie; the server is misconfigured and says
     * so loudly.
     */
    private AccAccount resolveBank() {
        String code = settings.requireString(SettingService.BANK_DEFAULT_ACCOUNT);
        AccAccount bank = accounts.findById(code).orElseThrow(() -> new IllegalStateException(
                "rc_setting['" + SettingService.BANK_DEFAULT_ACCOUNT + "'] names " + code
                        + ", which is not in the chart of accounts"));
        require(bank.isActive(),
                "the configured settlement bank " + code + " is not active. Point "
                        + SettingService.BANK_DEFAULT_ACCOUNT + " at a live account before "
                        + "recording settlements.");
        require("ASSET".equals(bank.getAccountType())
                        && ManualVoucherService.REPORT_GROUP_CASH_AND_BANK.equals(
                                bank.getReportGroup()),
                "the configured settlement bank " + code + " is " + bank.getAccountType() + " / "
                        + bank.getReportGroup() + ", and a settlement credits a cash or bank "
                        + "account.");
        return bank;
    }

    /** {@code UPI_SETTLEMENT:<collectionDate>:} — the trailing colon matters. */
    static String sequencePrefix(LocalDate collectionDate) {
        return SOURCE_UPI_SETTLEMENT + ":" + collectionDate + ":";
    }

    /**
     * The collection date out of {@code UPI_SETTLEMENT:<collectionDate>:<n>}.
     *
     * <p>Split rather than substring-by-offset, so the parse does not silently
     * follow the format drifting.
     */
    static LocalDate collectionDateOf(AccVoucher voucher) {
        String ref = voucher.getSourceRef();
        String[] parts = ref == null ? new String[0] : ref.split(":");
        if (parts.length != 3 || !SOURCE_UPI_SETTLEMENT.equals(parts[0])) {
            throw new IllegalStateException(
                    "settlement voucher " + voucher.getVoucherId() + " has SOURCE_REF '" + ref
                            + "', which is not UPI_SETTLEMENT:<collectionDate>:<n> — the "
                            + "collection date is recorded nowhere else on the voucher (§1.2)");
        }
        return LocalDate.parse(parts[1]);
    }

    private static LocalDate toLocalDate(Object value) {
        // MySQL answers a DATE as java.sql.Date through a native query; H2 can
        // answer a LocalDate. Both are handled rather than one being assumed.
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        return (LocalDate) value;
    }

    private static BigDecimal paise(BigDecimal amount) {
        return amount == null ? ZERO : amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String truncate(String narration) {
        return narration.length() <= 255 ? narration : narration.substring(0, 255);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new LedgerPostingException(message);
        }
    }
}
