package com.mangle.retailshopapp.accounting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.mangle.retailshopapp.accounting.model.AccAccount;
import com.mangle.retailshopapp.accounting.model.AccFloatBatch;
import com.mangle.retailshopapp.accounting.model.AccFloatMovement;
import com.mangle.retailshopapp.accounting.model.AccVoucher;
import com.mangle.retailshopapp.accounting.model.request.ContraRequest;
import com.mangle.retailshopapp.accounting.model.request.JournalRequest;
import com.mangle.retailshopapp.accounting.model.request.PaymentRequest;
import com.mangle.retailshopapp.accounting.model.request.PurchaseRequest;
import com.mangle.retailshopapp.accounting.model.response.VoucherResponse;
import com.mangle.retailshopapp.accounting.repo.AccAccountRepository;
import com.mangle.retailshopapp.accounting.repo.AccFloatBatchRepository;
import com.mangle.retailshopapp.accounting.repo.AccFloatMovementRepository;
import com.mangle.retailshopapp.accounting.repo.AccVoucherLineRepository;
import com.mangle.retailshopapp.accounting.repo.AccVoucherRepository;
import com.mangle.retailshopapp.settings.SettingService;

/**
 * The vouchers a person composes — endpoints 7, 8, 9 and 19 of
 * design-ledger-api.md §1.3.
 *
 * <h2>A5: one endpoint per voucher type, never a generic POST /vouchers</h2>
 *
 * A generic endpoint would have to validate six different leg shapes from one
 * payload, and would let a caller construct a {@code SALES} voucher by hand —
 * bypassing the float legs, the commission computation and the idempotency keys
 * that make those vouchers correct. {@code payments}, {@code contras},
 * {@code journals} and {@code purchases} each know their own shape, and only
 * {@code journals} accepts free legs because only a correction can touch any pair
 * of accounts.
 *
 * <h2>⚠ THIS IS THE FIRST SLICE WHERE A HUMAN COMPOSES A DRAFT</h2>
 *
 * Which is why {@code LedgerPostingException} had to join the exception tree
 * before these endpoints were written (next-session-s5.md item 5).
 * {@code RechargeLedgerPostings} only ever posted drafts the rules already
 * accept, so no refusal had ever reached a client, and the one that would have
 * arrived as a bare 500. Every refusal below is a 422 carrying its sentence —
 * and the sentence is the whole of what a client can act on, which is why each
 * one names the endpoint to use instead where there is one.
 *
 * <h2>Idempotency: the client's key, because only the client knows</h2>
 *
 * All four of these take a required {@code Idempotency-Key} header, unlike
 * endpoints 2 and 4 (§1.3, "Idempotency, endpoint by endpoint"). <b>Two identical
 * ₹1,200 electricity payments dated the same day might be one double-click or two
 * real bills, and nothing in the payload separates them.</b> A close can derive
 * its key because "close POOL-1 for 9 Nov" is true at most once; an expense has
 * no natural key, so the client's opaque value is stored on
 * {@code acc_voucher.IDEMPOTENCY_KEY} and {@code uk_vch_idem} is the guard.
 *
 * <p>⚠ <b>THE KEY IS CHECKED BEFORE ANYTHING IS WRITTEN, AND FOR #19 THAT IS A
 * CORRECTNESS REQUIREMENT RATHER THAN AN OPTIMISATION.</b>
 * {@link LedgerPostingService#post} does its own lookup first — its class comment
 * explains why catch-and-re-read cannot work inside one transaction — but a
 * top-up has side effects BESIDE the voucher: an {@code acc_float_batch} row and
 * an {@code acc_float_movement} row. If the replay were only noticed inside
 * {@code post}, this service would already have been about to write a second
 * batch for the same wallet. So the check is here, at the top, and
 * {@code uk_fbatch_voucher} remains the backstop for the race between two
 * concurrent first-posts.
 */
@Service
public class ManualVoucherService {

    /**
     * The IMPS fee's account, fixed by the posting rule rather than chosen by a
     * client (design-ledger.md E6:1559, §1496: "Two: {@code EXP_BANK_CHARGES}
     * (the flat ₹5 IMPS fee on every top-up) and {@code EXP_RCHG_LOSS}").
     *
     * <p>It stays recharge-scoped and was confirmed so (log §8.6): no equivalent
     * charge arises on ticket-booking transfers, so the account was NOT promoted
     * to a shared one. Had the answer gone the other way this would have been
     * business-neutral and the ticket posting would have needed a third leg — and
     * it would have been the larger of the two figures, since bookings are far
     * more frequent than top-ups.
     */
    static final String EXP_BANK_CHARGES = "EXP_BANK_CHARGES";

    /** {@code ref_voucher_source}: 'Hand-entered voucher' (V21:184). */
    static final String SOURCE_MANUAL = "MANUAL";

    /** {@code ref_voucher_source}: 'Operator float top-up' (V21:177). */
    static final String SOURCE_FLOAT_TOPUP = "FLOAT_TOPUP";

    /** {@code ref_float_movement_type}: 'Float purchased' (V21:198). */
    static final String MOVEMENT_TOPUP = "TOPUP";

    static final String REPORT_GROUP_CASH_AND_BANK = "CASH_AND_BANK";

    /** Scale 2, for the reason {@code LedgerCloseService.ZERO} gives. */
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final AccAccountRepository accounts;
    private final AccVoucherRepository vouchers;
    private final AccVoucherLineRepository voucherLines;
    private final AccFloatBatchRepository floatBatches;
    private final AccFloatMovementRepository floatMovements;
    private final LedgerPostingService postings;
    private final SettingService settings;
    private final Clock clock;

    public ManualVoucherService(AccAccountRepository accounts,
                                AccVoucherRepository vouchers,
                                AccVoucherLineRepository voucherLines,
                                AccFloatBatchRepository floatBatches,
                                AccFloatMovementRepository floatMovements,
                                LedgerPostingService postings,
                                SettingService settings,
                                Clock clock) {
        this.accounts = accounts;
        this.vouchers = vouchers;
        this.voucherLines = voucherLines;
        this.floatBatches = floatBatches;
        this.floatMovements = floatMovements;
        this.postings = postings;
        this.settings = settings;
        this.clock = clock;
    }

    // ==================================================================
    // Endpoint 7 — POST /vouchers/payments
    // ==================================================================

    /**
     * An expense: a category and the account it was paid from (E19, FRD §6).
     *
     * <p>{@code Dr <expense> / Cr <source>} as a {@code PAYMENT}.
     */
    @Transactional
    public VoucherResponse postPayment(PaymentRequest request, String idempotencyKey,
                                       Integer staffUserId) {
        Optional<VoucherResponse> replay = replayedInThisTransaction(idempotencyKey);
        if (replay.isPresent()) {
            return replay.get();
        }

        requireNotInFuture(request.voucherDate(), "voucherDate");
        BigDecimal amount = requirePositive(request.amount(), "amount");

        AccAccount expense = requireKnownAccount(request.expenseAccountCode());
        requireActive(expense);
        require("EXPENSE".equals(expense.getAccountType()),
                expense.getAccountCode() + " is " + expense.getAccountType() + ", and an expense "
                        + "needs an EXPENSE account. GET /accounts?manualOnly=true&type=EXPENSE "
                        + "lists the ones this endpoint accepts.");
        requireManual(expense);

        AccAccount source = requireKnownAccount(request.sourceAccountCode());
        requireSpendableAsset(source, "an expense is paid from");

        LocalDateTime now = LocalDateTime.now(clock);
        long voucherId = postings.post(new VoucherDraft(
                "PAYMENT", request.voucherDate(), now, request.narration(),
                SOURCE_MANUAL, null, idempotencyKey, null, staffUserId,
                List.of(VoucherLeg.debit(expense.getAccountCode(), amount),
                        VoucherLeg.credit(source.getAccountCode(), amount)))).voucherId();

        return responseFor(voucherId, false);
    }

    // ==================================================================
    // Endpoint 8 — POST /vouchers/contras
    // ==================================================================

    /**
     * Money between the shop's own accounts (E20): the safe to the bank, and the
     * manual sweep.
     *
     * <p>{@code Dr <to> / Cr <from>} as a {@code CONTRA}, which
     * {@code ref_voucher_type} marks {@code AFFECTS_PL = FALSE} — FRD §8.2's pure
     * asset relocation.
     */
    @Transactional
    public VoucherResponse postContra(ContraRequest request, String idempotencyKey,
                                      Integer staffUserId) {
        Optional<VoucherResponse> replay = replayedInThisTransaction(idempotencyKey);
        if (replay.isPresent()) {
            return replay.get();
        }

        requireNotInFuture(request.voucherDate(), "voucherDate");
        BigDecimal amount = requirePositive(request.amount(), "amount");

        // ⚠ Checked before the accounts are loaded, so the message is about the
        // mistake that was made rather than about whichever account happened to
        // be read first.
        require(!request.fromAccountCode().equals(request.toAccountCode()),
                "fromAccountCode and toAccountCode are both " + request.fromAccountCode()
                        + ". A Dr X / Cr X contra balances, passes every check and moves "
                        + "nothing.");

        AccAccount from = requireKnownAccount(request.fromAccountCode());
        AccAccount to = requireKnownAccount(request.toAccountCode());
        requireSpendableAsset(from, "money moves out of");
        requireSpendableAsset(to, "money moves into");

        LocalDateTime now = LocalDateTime.now(clock);
        String narration = request.narration() == null || request.narration().isBlank()
                ? "Transfer " + amount + " from " + from.getDisplayName() + " to "
                        + to.getDisplayName()
                : request.narration();

        long voucherId = postings.post(new VoucherDraft(
                "CONTRA", request.voucherDate(), now, narration,
                SOURCE_MANUAL, null, idempotencyKey, null, staffUserId,
                List.of(VoucherLeg.debit(to.getAccountCode(), amount),
                        VoucherLeg.credit(from.getAccountCode(), amount)))).voucherId();

        return responseFor(voucherId, false);
    }

    // ==================================================================
    // Endpoint 9 — POST /vouchers/journals
    // ==================================================================

    /**
     * A correction (FRD §8.3, E21), dated the day the mistake was found.
     *
     * <p>⚠ <b>ACCEPTED AT ANY TIME.</b> The 422 on "target account not yet
     * reconciled" was withdrawn on 2026-11-02: the module that owns the record
     * chooses between amending and journalling, and a ledger-native voucher has no
     * record to amend, so that refusal left an expense with no correction route at
     * all until the drawer was counted. See {@link JournalRequest}.
     *
     * <p>⚠ <b>{@code ALLOW_MANUAL = FALSE} IS STILL REFUSED</b> (A7). That is a
     * different rule and the two were never the same one.
     */
    @Transactional
    public VoucherResponse postJournal(JournalRequest request, String idempotencyKey,
                                       Integer staffUserId) {
        Optional<VoucherResponse> replay = replayedInThisTransaction(idempotencyKey);
        if (replay.isPresent()) {
            return replay.get();
        }

        requireNotInFuture(request.voucherDate(), "voucherDate");

        // ⚠ Two legs is checked here as well as in LedgerPostingService, because
        // the message a person needs differs: there it is "a voucher needs at
        // least two legs", which reads as an internal invariant; here the caller
        // typed the legs and needs to be told what to add.
        require(request.legs().size() >= 2,
                "a journal needs at least two legs — " + request.legs().size() + " was sent. "
                        + "Double entry with one leg is not double entry: name the account the "
                        + "money came from as well as the one it goes to.");

        if (request.reversesVoucherId() != null) {
            // Explicitly, rather than letting fk_voucher_reverses answer. A
            // foreign-key violation would arrive as a 409 DATA_INTEGRITY_VIOLATION
            // or a bare integrity error, when the honest answer is that the
            // voucher being corrected does not exist.
            require(vouchers.existsById(request.reversesVoucherId()),
                    "no such voucher to reverse: " + request.reversesVoucherId());
        }

        List<VoucherLeg> legs = new ArrayList<>(request.legs().size());
        BigDecimal totalDr = ZERO;
        BigDecimal totalCr = ZERO;
        for (JournalRequest.JournalLeg leg : request.legs()) {
            BigDecimal dr = leg.dr() == null ? ZERO : leg.dr();
            BigDecimal cr = leg.cr() == null ? ZERO : leg.cr();
            require(dr.signum() >= 0 && cr.signum() >= 0,
                    "leg amounts cannot be negative: " + leg.accountCode()
                            + ". A negative debit is a credit — put it on the other side.");
            require(dr.signum() == 0 || cr.signum() == 0,
                    "a leg is a debit or a credit, never both: " + leg.accountCode());
            require(dr.signum() > 0 || cr.signum() > 0,
                    "a leg cannot be zero: " + leg.accountCode()
                            + ". Leave it out instead — ck_line_nonzero refuses it anyway.");

            AccAccount account = requireKnownAccount(leg.accountCode());
            requireActive(account);
            // A7, and the one check in this method that is not also in
            // LedgerPostingService: that service deliberately does NOT test
            // ALLOW_MANUAL, because the automatic postings it serves legitimately
            // move the machine-owned accounts.
            requireManual(account);
            require(!account.isRequiresParty() || leg.partyCustId() != null,
                    "account " + account.getAccountCode() + " requires a partyCustId: an "
                            + account.getReportGroup() + " leg with no customer cannot appear on "
                            + "anyone's statement (FRD 8.7).");

            VoucherLeg built = dr.signum() > 0
                    ? VoucherLeg.debit(account.getAccountCode(), dr)
                    : VoucherLeg.credit(account.getAccountCode(), cr);
            if (leg.partyCustId() != null) {
                built = built.withParty(leg.partyCustId());
            }
            if (leg.lineNarration() != null && !leg.lineNarration().isBlank()) {
                built = built.withNarration(leg.lineNarration());
            }
            legs.add(built);
            totalDr = totalDr.add(dr);
            totalCr = totalCr.add(cr);
        }

        // compareTo, not equals: BigDecimal.equals treats 100.0 and 100.00 as
        // different and would refuse a correctly balanced journal.
        require(totalDr.compareTo(totalCr) == 0,
                "the journal does not balance: DR " + totalDr + " vs CR " + totalCr
                        + ". The difference is " + totalDr.subtract(totalCr).abs() + ".");

        LocalDateTime now = LocalDateTime.now(clock);
        long voucherId = postings.post(new VoucherDraft(
                "JOURNAL", request.voucherDate(), now, request.narration(),
                // CORRECTION rather than MANUAL when it names what it reverses:
                // ref_voucher_source has both, and 'Correcting journal (FRD 8.3)'
                // is what a reader of acc_voucher wants to see on one.
                request.reversesVoucherId() == null ? SOURCE_MANUAL : "CORRECTION",
                null, idempotencyKey, request.reversesVoucherId(), staffUserId,
                legs)).voucherId();

        return responseFor(voucherId, false);
    }

    // ==================================================================
    // Endpoint 19 — POST /vouchers/purchases
    // ==================================================================

    /**
     * A float top-up (E6/E7, log §2.5), with its batch and its movement row.
     *
     * <p>Three legs: {@code Dr <float>} by the face received,
     * {@code Dr EXP_BANK_CHARGES} by the transfer fee, {@code Cr <source>} by the
     * two together.
     *
     * <p>⚠ <b>TWO LEGS WHEN THE FEE IS ZERO</b>, which is E7's cash top-up —
     * "no IMPS fee arises" — and is not a special case so much as the zero-leg
     * rule applying again: {@code ck_line_nonzero CHECK (DR_AMOUNT + CR_AMOUNT >
     * 0)} refuses {@code Dr EXP_BANK_CHARGES 0.00} and would fail the whole
     * top-up. §1.3 describes three legs because its example is an IMPS transfer.
     *
     * <p>⚠ <b>THE FEE IS NOT IN THE BATCH'S {@code COST_PAID}</b>
     * (log §2.5:114-118). {@code FACE_ADDED} and {@code COST_PAID} both carry the
     * ₹10,000; the ₹5 is an expense. Folding it in would make the ratio
     * 10000/10005 and the portal-versus-books comparison would stop being a
     * straight one — which is the only independent check the design has on float.
     */
    @Transactional
    public VoucherResponse postPurchase(PurchaseRequest request, String idempotencyKey,
                                        Integer staffUserId) {
        Optional<VoucherResponse> replay = replayedInThisTransaction(idempotencyKey);
        if (replay.isPresent()) {
            // ⚠ AND NOTHING BELOW RUNS, WHICH IS THE POINT. A second batch for
            // the same top-up would double a wallet's cost basis;
            // uk_fbatch_voucher would refuse it, but as a constraint violation
            // rather than as the 409 the caller deserves.
            return replay.get();
        }

        requireNotInFuture(request.voucherDate(), "voucherDate");
        BigDecimal amount = requirePositive(request.amount(), "amount");

        AccAccount wallet = requireKnownAccount(request.floatAccountCode());
        requireActive(wallet);
        require(wallet.getFloatPattern() != null,
                wallet.getAccountCode() + " is not a float account: it has no FLOAT_PATTERN, "
                        + "which is what makes an account a wallet (V21). A top-up only means "
                        + "something for one of the four floats.");

        AccAccount source = requireKnownAccount(request.sourceAccountCode());
        requireSpendableAsset(source, "a top-up is paid from");

        // The fee defaults to the setting, and 0 is a legitimate value a client
        // sends rather than an omission (E7). Absent means "use the configured
        // IMPS fee"; zero means "this top-up was not an IMPS transfer".
        BigDecimal fee = request.transferFee() == null
                ? paise(settings.floatImpsFee())
                : paise(request.transferFee());
        require(fee.signum() >= 0,
                "transferFee cannot be negative: " + fee + ". A fee the shop receives is not a "
                        + "fee; post it as income through a journal.");

        // §1.3's 422: a supplied costPaid differing from amount while the
        // operator is pattern 2 — and all four are (log §2.4:97-99), so the
        // comparison is unconditional today. The field exists for the day a
        // bonus-on-topup wallet is added, and refusing a disagreement is what
        // keeps the ratio verifiable until then.
        BigDecimal costPaid = request.costPaid() == null ? amount : paise(request.costPaid());
        require(costPaid.compareTo(amount) == 0,
                "costPaid (" + costPaid + ") differs from amount (" + amount + "), and "
                        + wallet.getAccountCode() + " is FLOAT_PATTERN " + wallet.getFloatPattern()
                        + " — pay Rs X, receive Rs X, so cost equals face and the ratio is "
                        + "exactly 1.0 (FRD 7.2.2, log 2.4). A bonus-on-topup wallet is where "
                        + "the two legitimately differ, and the shop has none.");

        LocalDateTime now = LocalDateTime.now(clock);
        String narration = request.narration() == null || request.narration().isBlank()
                ? "Float top-up " + amount + " into " + wallet.getDisplayName()
                : request.narration();

        List<VoucherLeg> legs = new ArrayList<>(3);
        legs.add(VoucherLeg.debit(wallet.getAccountCode(), amount));
        if (fee.signum() > 0) {
            legs.add(VoucherLeg.debit(EXP_BANK_CHARGES, fee)
                    .withNarration("Transfer fee on " + amount + " into "
                            + wallet.getAccountCode()));
        }
        legs.add(VoucherLeg.credit(source.getAccountCode(), amount.add(fee)));

        long voucherId = postings.post(new VoucherDraft(
                "PURCHASE", request.voucherDate(), now, narration,
                SOURCE_FLOAT_TOPUP, request.externalRef(), idempotencyKey, null,
                staffUserId, legs)).voucherId();

        recordBatch(wallet, request, amount, costPaid, voucherId, now, staffUserId);

        return responseFor(voucherId, false);
    }

    /**
     * The float side of a top-up: one batch and one movement row (§3.2).
     *
     * <p>⚠ IN THIS ORDER AND INSIDE THE SAME TRANSACTION.
     * {@code fk_fbatch_voucher} points at the voucher and {@code fk_fmov_batch} at
     * the batch, so the voucher is posted first and the movement last;
     * {@code ck_fmov_topup} then has the {@code BATCH_ID} it demands on a
     * {@code TOPUP}. A batch without its movement would be a purchase that never
     * reached the wallet, and §2's reasoning for every other write applies
     * unchanged: a partial record is the worst possible state.
     *
     * <p>{@code APPLIED_WA} is left null. It records the ratio that WAS applied to
     * a consumption; a top-up SETS the ratio rather than applying one, and writing
     * 1.0 here would claim a weighted average had been computed when none was.
     */
    private void recordBatch(AccAccount wallet, PurchaseRequest request, BigDecimal faceAdded,
                             BigDecimal costPaid, long voucherId, LocalDateTime now,
                             Integer staffUserId) {
        AccFloatBatch batch = new AccFloatBatch();
        batch.setAccountCode(wallet.getAccountCode());
        batch.setTopupVoucherId(voucherId);
        batch.setFaceAdded(faceAdded);
        batch.setCostPaid(costPaid);
        batch.setAcquiredOn(request.voucherDate());
        batch.setExternalRef(request.externalRef());
        batch.setNote(request.narration());
        batch.setCreBy(staffUserId);
        AccFloatBatch saved = floatBatches.saveAndFlush(batch);

        AccFloatMovement movement = new AccFloatMovement();
        movement.setAccountCode(wallet.getAccountCode());
        movement.setMovementType(MOVEMENT_TOPUP);
        // Signed, and positive on a top-up: face and cost both rise.
        movement.setFaceDelta(faceAdded);
        movement.setCostDelta(costPaid);
        movement.setBatchId(saved.getBatchId());
        movement.setVoucherId(voucherId);
        movement.setMovedAt(now);
        floatMovements.saveAndFlush(movement);
    }

    // ==================================================================
    // The replay reads
    // ==================================================================

    /**
     * The ordinary replay, inside the caller's own transaction.
     *
     * <p>Nothing has been written at the point this runs, so there is no rollback
     * to see past — which is what makes a plain read correct here and makes
     * {@link #replayed(String)} necessary for the other case.
     */
    private Optional<VoucherResponse> replayedInThisTransaction(String idempotencyKey) {
        return vouchers.findByIdempotencyKey(idempotencyKey)
                .map(v -> responseFor(v.getVoucherId(), true));
    }

    /**
     * ⚠ A FRESH READ-ONLY TRANSACTION, for the controller's duplicate-key catch
     * and nothing else.
     *
     * <p>{@code REQUIRES_NEW} because the caller reaches here from a catch block
     * after its own transaction has rolled back, so the voucher that caused the
     * duplicate is invisible from inside the aborted one
     * (RechargeController.java:240-247). This path is the RACE between two
     * concurrent first-posts, not the ordinary retry: an ordinary retry is caught
     * by the lookup above before anything is written.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<VoucherResponse> replayed(String idempotencyKey) {
        return vouchers.findByIdempotencyKey(idempotencyKey)
                .map(v -> responseFor(v.getVoucherId(), true));
    }

    // ==================================================================
    // Validation
    // ==================================================================

    /**
     * An {@code ASSET} the shop spends from: {@code CASH_AND_BANK} with
     * {@code ALLOW_MANUAL = TRUE}. Eight accounts — the three tills, the safe and
     * the four banks.
     *
     * <p>⚠ <b>§1.3 WRITES THIS RULE TWICE AND NOT IDENTICALLY, AND THE STRICTER
     * READING IS APPLIED TO ALL THREE ENDPOINTS.</b> For #8 it is "an {@code ASSET}
     * in {@code REPORT_GROUP = 'CASH_AND_BANK'} with {@code ALLOW_MANUAL = TRUE}";
     * for #19 it is "an asset account with {@code ALLOW_MANUAL = TRUE}", with no
     * report group; for #7 no list is given at all. <b>The three sets are
     * identical today</b> — every {@code ASSET} with {@code ALLOW_MANUAL = TRUE}
     * that V21 seeds is in {@code CASH_AND_BANK} — so one check serves all three
     * and no endpoint's behaviour differs from its specification.
     *
     * <p>If an {@code ASSET} with {@code ALLOW_MANUAL = TRUE} outside
     * {@code CASH_AND_BANK} is ever seeded — a prepayment, a deposit — this check
     * refuses it on all three. That is the conservative direction and it is also
     * the correct one: an expense, a transfer and a top-up are all paid out of
     * cash or a bank, and none of them is paid out of a receivable.
     *
     * @param role how the account is about to be used, for the message
     */
    private void requireSpendableAsset(AccAccount account, String role) {
        requireActive(account);
        // ⚠ A7 FIRST, AND THE ORDER WAS CHANGED AFTER RUNNING IT. With the
        // report-group check first, a contra naming FLOAT_RJIO was refused as
        // "ASSET / FLOAT, and this endpoint takes a cash or bank account" — true,
        // unhelpful, and it buries the thing the caller needs, which is that a
        // float IS topped up and corrected, just not here. Every machine-owned
        // account this method rejects is one the ledger moves constantly, so the
        // A7 sentence naming the owning endpoint is the more actionable refusal
        // and goes first. An ordinary mistake — an expense account on a contra —
        // still falls through to the shape message below.
        requireManual(account);
        require("ASSET".equals(account.getAccountType())
                        && REPORT_GROUP_CASH_AND_BANK.equals(account.getReportGroup()),
                account.getAccountCode() + " is not an account " + role + ": it is "
                        + account.getAccountType() + " / " + account.getReportGroup()
                        + ", and this endpoint takes a cash or bank account "
                        + "(ASSET, REPORT_GROUP CASH_AND_BANK).");
    }

    /**
     * A7, with the sentence naming the endpoint that DOES own the account.
     *
     * <p>⚠ <b>"NOT ALLOWED" IS NOT A USEFUL REFUSAL HERE</b>, because every
     * account this rejects is one the ledger moves constantly — just not from
     * here. {@code ALLOW_MANUAL = FALSE} keeps an account out of the GENERIC
     * endpoints, not out of the ledger (A7), so the client's next action is a
     * different endpoint rather than a different account, and the message has to
     * say which.
     */
    private void requireManual(AccAccount account) {
        if (account.isAllowManual()) {
            return;
        }
        throw new LedgerPostingException(
                account.getAccountCode() + " is ALLOW_MANUAL = FALSE and cannot carry a "
                        + "hand-posted leg (A7). " + whatOwns(account));
    }

    /**
     * Which endpoint moves each machine-owned account, so a refusal can say so.
     *
     * <p>⚠ Listed rather than derived, because the reason differs per account and
     * a generic sentence would be useless. The five cases are every
     * {@code ALLOW_MANUAL = FALSE} account V21 seeds that a person might
     * plausibly type.
     */
    private static String whatOwns(AccAccount account) {
        if (account.getFloatPattern() != null) {
            return "A float moves two ways and neither is a journal: POST /vouchers/purchases "
                    + "for a top-up (endpoint 19), and POST /accounts/" + account.getAccountCode()
                    + "/reconciliations with reason RATE_CORRECTION for a balance correction "
                    + "(endpoint 4, log 14.9).";
        }
        return switch (account.getAccountCode()) {
            case "UPI_CLEARING" ->
                    "UPI_CLEARING is settled by POST /accounts/upi-clearing/settlements "
                            + "(endpoint 6), which records what the bank actually credited and "
                            + "leaves any remainder in transit. ⚠ A residue that is genuinely "
                            + "lost is written off by closing UPI_CLEARING through POST "
                            + "/accounts/UPI_CLEARING/reconciliations (endpoint 4) with an "
                            + "explained variance naming a real expense account — never "
                            + "automatically, and never through this endpoint.";
            case "RCHG_PENDING" ->
                    "RCHG_PENDING is consumed by the recharge flow that created it — a retry, a "
                            + "refund or a residue settlement (design-ledger.md E10b) — each of "
                            + "which knows how the original was tendered.";
            case "EXP_CASH_SHORT", "INC_CASH_OVER" ->
                    "The cash variance accounts are posted by the close itself, for the "
                            + "genuinely unexplained (log 9.1). If the cause IS known, name the "
                            + "real account in the close's explained block instead; if a past "
                            + "close was wrong, correct the real accounts and leave these alone.";
            case "INC_RCHG_COMM", "INC_RCHG_SURCHARGE", "INC_WATER_TRIP", "INC_TKT_COMM" ->
                    "Income is recognised by the sale that earned it, never by hand — a "
                            + "hand-posted commission would break INC_RCHG_COMM = SUM(A x r), "
                            + "which is the only check the design has against the rate card "
                            + "(design-ledger.md 11.1).";
            default ->
                    "It is moved by the posting rule that owns it. GET /accounts?manualOnly=true "
                            + "lists what this endpoint will accept.";
        };
    }

    private void requireActive(AccAccount account) {
        require(account.isActive(),
                "account is not active: " + account.getAccountCode()
                        + ". It was retired, and a retired account must not take new postings.");
    }

    private AccAccount requireKnownAccount(String accountCode) {
        return accounts.findById(accountCode).orElseThrow(
                () -> new LedgerPostingException("no such account: " + accountCode
                        + ". GET /accounts lists the chart of accounts."));
    }

    /**
     * ⚠ A voucher dated in the future is money that has not moved yet, and the
     * close is what makes that expensive: the lock test is
     * {@code RECON_DATE >= VOUCHER_DATE}, so a voucher dated next Friday sits
     * unsealed by every close until then, outside the correction regime FRD §8.3
     * describes.
     */
    private void requireNotInFuture(LocalDate date, String field) {
        LocalDate today = LocalDate.now(clock);
        require(!date.isAfter(today),
                field + " " + date + " is in the future (today is " + today + "). A voucher "
                        + "records money that has already moved.");
    }

    private static BigDecimal requirePositive(BigDecimal amount, String field) {
        BigDecimal scaled = paise(amount);
        require(scaled.signum() > 0,
                field + " must be positive; " + scaled + " was sent. The direction is carried by "
                        + "the legs, never by a negative amount.");
        return scaled;
    }

    /**
     * {@code UNNECESSARY} rather than a rounding mode, for the reason
     * {@code settlement.Amounts} gives: money finer than a paisa is a programming
     * error, not something to round away. A client sending {@code 1200.005} is
     * told so rather than having it silently become {@code 1200.01}.
     */
    private static BigDecimal paise(BigDecimal amount) {
        return amount == null ? ZERO : amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new LedgerPostingException(message);
        }
    }

    // ==================================================================
    // Plumbing
    // ==================================================================

    /**
     * The response, read back from the stored voucher rather than echoed from the
     * request.
     *
     * <p>⚠ That is not pedantry: S4 shipped a replayed booking that came back with
     * no payment and no voucher on it, because the response echoed a request the
     * second call never made. A reply built from the row is correct on the first
     * post and on every replay, with no second code path to keep in step.
     */
    private VoucherResponse responseFor(long voucherId, boolean replayed) {
        AccVoucher voucher = vouchers.findById(voucherId).orElseThrow(
                () -> new IllegalStateException("voucher " + voucherId + " vanished after posting"));
        return VoucherResponse.from(
                voucher, voucherLines.findByVoucherIdOrderByLineNo(voucherId), replayed);
    }
}
