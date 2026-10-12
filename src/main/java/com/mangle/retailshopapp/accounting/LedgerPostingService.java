package com.mangle.retailshopapp.accounting;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.mangle.retailshopapp.accounting.model.AccAccount;
import com.mangle.retailshopapp.accounting.model.AccVoucher;
import com.mangle.retailshopapp.accounting.model.AccVoucherLine;
import com.mangle.retailshopapp.accounting.repo.AccAccountRepository;
import com.mangle.retailshopapp.accounting.repo.AccVoucherLineRepository;
import com.mangle.retailshopapp.accounting.repo.AccVoucherRepository;

/**
 * The ONLY way anything is written to {@code acc_voucher} / {@code acc_voucher_line}.
 *
 * <p>design-ledger.md §5 and L9. One service, one method, called from inside the
 * existing operational {@code @Transactional} methods. §5.1 records the rejected
 * alternatives — database triggers, Spring events, a background job — and they
 * are not to be revisited.
 *
 * <h2>Why {@code MANDATORY} and not {@code REQUIRED}</h2>
 *
 * §5.1 calls this "the single most valuable line in this design", and the reason
 * is specific. With {@code REQUIRED}, a future caller that forgets
 * {@code @Transactional} silently gets its own transaction, the ledger write
 * commits separately from the operational write, and FRD §8.5's single-source-of-
 * truth guarantee quietly evaporates — with nothing failing to say so. With
 * {@code MANDATORY} that caller throws {@code IllegalTransactionStateException}
 * on its first call, in its first test. "Same transaction" becomes structural
 * rather than remembered.
 *
 * <p>Nothing in the posting path uses {@code REQUIRES_NEW}, for the same reason.
 *
 * <h2>The three-step post, and why it is not a cascade</h2>
 *
 * MySQL has no deferred constraints, so "the legs sum to zero" cannot be a
 * {@code CHECK} — it would reject the first leg of a two-leg voucher. What works
 * is a trigger on the parent, which can aggregate over the children (§2.4). So:
 *
 * <ol>
 *   <li>insert the header with {@code SEALED_AT} null, and flush to get its id;
 *   <li>insert the legs, and flush;
 *   <li>set {@code SEALED_AT} and flush — V23's trigger 1 sums the legs here and
 *       refuses an unbalanced or single-legged voucher.
 * </ol>
 *
 * <p>Each step is flushed explicitly rather than mapped as a cascading
 * {@code @OneToMany}, because with a cascade Hibernate chooses the statement
 * order and the seal could reach the database before the legs do. The trigger
 * would then see no children and reject a perfectly good voucher.
 *
 * <p>Every report filters on {@code SEALED_AT IS NOT NULL}, so a voucher
 * interrupted between steps is invisible rather than wrong.
 *
 * <h2>Idempotency: checked first, with the unique index as the backstop</h2>
 *
 * ⚠ This departs from §5.4's wording, which says the service "catches
 * {@code DuplicateKeyException}, re-reads the existing voucher, and either
 * returns it or rethrows as HTTP 409". <b>Catch-and-re-read cannot work inside
 * one transaction.</b> A constraint violation marks the transaction
 * rollback-only, so the re-read would run in a doomed transaction and the commit
 * would fail anyway — and here the transaction belongs to the CALLER, carrying
 * its operational write with it.
 *
 * <p>So the lookup happens BEFORE the insert, and {@code uk_vch_idem} remains the
 * real guarantee: it closes the race between two concurrent first-posts, on
 * every path including hand-SQL, which application-side bookkeeping could not. On
 * that race the exception propagates and the caller's retry finds the row at the
 * lookup. The observable contract §5.4 asks for is unchanged — a second post
 * writes nothing and the existing voucher comes back — only the mechanism
 * differs, and this is the one that survives being inside someone else's
 * transaction.
 */
@Service
public class LedgerPostingService {

    private final AccVoucherRepository voucherRepository;
    private final AccVoucherLineRepository lineRepository;
    private final AccAccountRepository accountRepository;

    public LedgerPostingService(AccVoucherRepository voucherRepository,
                                AccVoucherLineRepository lineRepository,
                                AccAccountRepository accountRepository) {
        this.voucherRepository = voucherRepository;
        this.lineRepository = lineRepository;
        this.accountRepository = accountRepository;
    }

    /**
     * Posts one balanced voucher inside the caller's transaction.
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException
     *         if called with no transaction in progress — see the class comment
     * @throws LedgerPostingException if the draft breaks a posting rule
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PostedVoucher post(VoucherDraft draft) {
        validate(draft);

        // Before the insert, not after a failure. See the class comment.
        if (draft.idempotencyKey() != null) {
            Optional<AccVoucher> existing =
                    voucherRepository.findByIdempotencyKey(draft.idempotencyKey());
            if (existing.isPresent()) {
                AccVoucher v = existing.get();
                return new PostedVoucher(v.getVoucherId(), v.getSealedAt(),
                        lineRepository.findByVoucherIdOrderByLineNo(v.getVoucherId()).size(), true);
            }
        }

        // Step 1 - the header, unsealed.
        AccVoucher voucher = new AccVoucher();
        voucher.setVoucherType(draft.voucherType());
        voucher.setVoucherDate(draft.voucherDate());
        voucher.setVoucherDttm(draft.voucherDttm());
        voucher.setNarration(draft.narration());
        voucher.setSourceType(draft.sourceType());
        voucher.setSourceRef(draft.sourceRef());
        voucher.setIdempotencyKey(draft.idempotencyKey());
        voucher.setReversesVoucherId(draft.reversesVoucherId());
        voucher.setCreBy(draft.creBy());
        voucher.setSealedAt(null);
        voucherRepository.saveAndFlush(voucher);

        // Step 2 - the legs. LINE_NO is assigned here, 1..n, so a caller never
        // has to and uk_line_voucher_no cannot be violated by a gap or a repeat.
        List<AccVoucherLine> lines = new ArrayList<>(draft.legs().size());
        short lineNo = 1;
        for (VoucherLeg leg : draft.legs()) {
            AccVoucherLine line = new AccVoucherLine();
            line.setVoucherId(voucher.getVoucherId());
            line.setLineNo(lineNo++);
            line.setAccountCode(leg.accountCode());
            line.setDrAmount(leg.drOrZero());
            line.setCrAmount(leg.crOrZero());
            line.setPartyCustId(leg.partyCustId());
            line.setPaymentMethod(leg.paymentMethod());
            line.setLineNarration(leg.lineNarration());
            lines.add(line);
        }
        lineRepository.saveAllAndFlush(lines);

        // Step 3 - seal. V23's trigger 1 asserts the balance at this moment.
        // SEALED_AT is when the seal happened, not the accounting date. The
        // accounting date is VOUCHER_DATE and the event time is VOUCHER_DTTM;
        // conflating any of the three would make a back-dated voucher look as
        // though it was sealed in the past.
        voucher.setSealedAt(LocalDateTime.now());
        voucherRepository.saveAndFlush(voucher);

        return new PostedVoucher(voucher.getVoucherId(), voucher.getSealedAt(), lines.size(), false);
    }

    /**
     * The edge checks. Each one is also enforced by the database independently —
     * these exist to return a sentence rather than a {@code SIGNAL SQLSTATE
     * '45000'} (design-ledger-api.md §1.3).
     *
     * <p>{@code ALLOW_MANUAL} is deliberately NOT checked here. A7 scopes it to
     * the generic voucher endpoints, not to the ledger: the float accounts,
     * {@code UPI_CLEARING} and {@code RCHG_PENDING} are machine-owned and are
     * moved by the automatic postings that own them. Enforcing it in the service
     * would block the very callers it exists to protect.
     */
    private void validate(VoucherDraft draft) {
        if (draft == null) {
            throw new LedgerPostingException("a voucher draft is required");
        }
        require(draft.voucherType() != null, "VOUCHER_TYPE is required");
        require(draft.voucherDate() != null, "VOUCHER_DATE is required");
        require(draft.voucherDttm() != null, "VOUCHER_DTTM is required");
        require(draft.sourceType() != null, "SOURCE_TYPE is required");

        List<VoucherLeg> legs = draft.legs();
        // Mirrors V23 trigger 1's "a voucher needs at least two legs". Double
        // entry with one leg is not double entry.
        require(legs != null && legs.size() >= 2, "a voucher needs at least two legs");

        BigDecimal totalDr = BigDecimal.ZERO;
        BigDecimal totalCr = BigDecimal.ZERO;
        for (VoucherLeg leg : legs) {
            require(leg.accountCode() != null, "every leg needs an ACCOUNT_CODE");
            BigDecimal dr = leg.drOrZero();
            BigDecimal cr = leg.crOrZero();
            require(dr.signum() >= 0 && cr.signum() >= 0,
                    "leg amounts cannot be negative: " + leg.accountCode());
            require(dr.signum() == 0 || cr.signum() == 0,
                    "a leg is a debit or a credit, never both: " + leg.accountCode());
            require(dr.signum() > 0 || cr.signum() > 0,
                    "a leg cannot be zero: " + leg.accountCode());
            totalDr = totalDr.add(dr);
            totalCr = totalCr.add(cr);
        }

        // compareTo, not equals: BigDecimal.equals treats 100.0 and 100.00 as
        // different, which would reject a correctly balanced voucher.
        require(totalDr.compareTo(totalCr) == 0,
                "voucher does not balance: DR " + totalDr + " vs CR " + totalCr);

        validateAccounts(legs);
    }

    private void validateAccounts(List<VoucherLeg> legs) {
        List<String> codes = legs.stream().map(VoucherLeg::accountCode).distinct().toList();
        Map<String, AccAccount> found = new HashMap<>();
        for (AccAccount a : accountRepository.findByAccountCodeIn(codes)) {
            found.put(a.getAccountCode(), a);
        }
        for (VoucherLeg leg : legs) {
            AccAccount account = found.get(leg.accountCode());
            require(account != null, "no such account: " + leg.accountCode());
            require(account.isActive(), "account is not active: " + leg.accountCode());
            // AR_* and ADV_* carry a customer dimension; a receivable with no
            // party cannot appear on anyone's statement (FRD §8.7).
            require(!account.isRequiresParty() || leg.partyCustId() != null,
                    "account " + leg.accountCode() + " requires a partyCustId");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new LedgerPostingException(message);
        }
    }
}
