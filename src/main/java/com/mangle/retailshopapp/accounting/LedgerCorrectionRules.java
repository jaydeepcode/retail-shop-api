package com.mangle.retailshopapp.accounting;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.mangle.retailshopapp.accounting.model.AccVoucher;
import com.mangle.retailshopapp.accounting.repo.AccVoucherRepository;

import jakarta.persistence.EntityManager;

/**
 * The two rules that moved out of the database on 2026-10-09.
 *
 * <p>V23 originally carried five triggers and V24/V25 one each. Four were
 * removed after the owner challenged how much business logic belongs in the
 * database. The split that settled it: <b>"a posted money row is never updated"
 * is a storage property</b> — no more business logic than {@code NOT NULL} — and
 * it is better served by a per-table {@code GRANT} that never grants
 * {@code UPDATE} than by a trigger. But <b>"an account reconciled past this date
 * can only be corrected by a journal" and "a rate is closed and superseded,
 * never edited" are accounting policy</b>, and policy belongs here, where it can
 * return a sentence rather than a raw {@code SIGNAL SQLSTATE '45000'}.
 *
 * <p><b>Why this class exists now, when nothing calls it yet.</b> S2 ships no
 * delete, void or rate-admin path — {@code LedgerPostingService.post} is the
 * only write. Removing the triggers without writing these would not be moving
 * the rules to the service, it would be deleting them. They are written and
 * tested here so the rule demonstrably exists in the backend; S5's void and
 * correction endpoints and the rate-admin screen are what wire them up.
 *
 * <p><b>What is genuinely weaker than before.</b> Until those callers exist,
 * nothing stops hand-SQL deleting a reconciled voucher's lines or editing a rate
 * row. The trigger did stop that. This is the accepted cost of the layering
 * decision, recorded rather than glossed: the rules are enforced for every path
 * that goes through the application, and not for paths that do not.
 */
@Component
public class LedgerCorrectionRules {

    private final AccVoucherRepository voucherRepository;
    private final EntityManager entityManager;

    public LedgerCorrectionRules(AccVoucherRepository voucherRepository,
                                 EntityManager entityManager) {
        this.voucherRepository = voucherRepository;
        this.entityManager = entityManager;
    }

    /**
     * Refuses to delete a voucher once any account it touches has been
     * reconciled on or after its own date — FRD §8.3's correction regime.
     *
     * <p>Before the reconciliation point a voucher may be deleted and re-posted,
     * which from the user's point of view is a direct edit. After it, the only
     * route is a new {@code JOURNAL} carrying {@code reversesVoucherId}, and the
     * original stays byte-identical.
     *
     * <p>The {@code OPENING} check is duplicated from V23's surviving trigger on
     * purpose: the trigger is the guarantee, this is the readable message.
     */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public void assertVoucherDeletable(long voucherId) {
        AccVoucher voucher = voucherRepository.findById(voucherId).orElseThrow(
                () -> new LedgerPostingException("no such voucher: " + voucherId));

        if ("OPENING".equals(voucher.getSourceType())) {
            throw new LedgerPostingException(
                    "the opening balance voucher cannot be deleted; every later balance "
                            + "stands on it and there is no operational record to re-post it from");
        }

        // One query, not one per leg: "is any account this voucher touches
        // reconciled at or after the voucher's own date".
        @SuppressWarnings("unchecked")
        List<String> locked = entityManager.createNativeQuery(
                        "SELECT DISTINCT r.ACCOUNT_CODE FROM acc_reconciliation r "
                                + "JOIN acc_voucher_line l ON l.ACCOUNT_CODE = r.ACCOUNT_CODE "
                                + "WHERE l.VOUCHER_ID = :vid AND r.RECON_DATE >= :vdate "
                                + "ORDER BY r.ACCOUNT_CODE")
                .setParameter("vid", voucherId)
                .setParameter("vdate", voucher.getVoucherDate())
                .getResultList();

        if (!locked.isEmpty()) {
            throw new LedgerPostingException(
                    "cannot delete voucher " + voucherId + ": " + String.join(", ", locked)
                            + " already reconciled on or after " + voucher.getVoucherDate()
                            + ". Post a correcting JOURNAL instead (FRD 8.3).");
        }
    }

    /**
     * Refuses an edit to a commission rate that is not a legitimate close.
     *
     * <p>"Editing" a rate means closing the open row and inserting a successor
     * (§4.1). Three ways that goes wrong, each with its own message.
     *
     * <p>⚠ §4.1 is explicit that this was never the real guarantee anyway, and
     * that is why it was the easiest of the four to move. It names two mechanisms
     * and says the second is the one that holds: <i>the posted amount is the
     * record, not the rate.</i> Historical P&amp;L is a {@code SUM} over
     * {@code acc_voucher_line}, so even a successful attack on the rate table
     * cannot move a reported figure. The rate table is an input log for future
     * postings, never the source of a reported number.
     *
     * @param currentEffectiveTo the row's existing {@code EFFECTIVE_TO}, or null if open
     * @param newEffectiveTo     the date it is being closed on
     * @param effectiveFrom      the row's {@code EFFECTIVE_FROM}
     * @param today              injected rather than read from the clock, so the
     *                           boundary is testable
     */
    public void assertRateClosable(LocalDate currentEffectiveTo, LocalDate newEffectiveTo,
                                   LocalDate effectiveFrom, LocalDate today) {
        if (currentEffectiveTo != null) {
            throw new LedgerPostingException(
                    "this rate was already closed on " + currentEffectiveTo
                            + "; EFFECTIVE_TO cannot be changed. Insert a successor instead.");
        }
        if (newEffectiveTo == null) {
            throw new LedgerPostingException("a close needs an EFFECTIVE_TO date");
        }
        if (newEffectiveTo.isBefore(today)) {
            throw new LedgerPostingException(
                    "a rate cannot be closed in the past (" + newEffectiveTo + "); that would "
                            + "restate commission on sales already posted at this rate. Close it "
                            + "today and post a dated correcting JOURNAL for the difference.");
        }
        if (newEffectiveTo.isBefore(effectiveFrom)) {
            throw new LedgerPostingException(
                    "EFFECTIVE_TO (" + newEffectiveTo + ") cannot precede EFFECTIVE_FROM ("
                            + effectiveFrom + ")");
        }
    }

    /**
     * The identifying columns of a rate row are immutable: a change to any of
     * them is a different rate, which means a new row (§4.1).
     */
    public void assertRateIdentityUnchanged(boolean operatorChanged, boolean routeChanged,
                                            boolean tierChanged, boolean bandChanged,
                                            boolean rateChanged, boolean effectiveFromChanged) {
        if (operatorChanged || routeChanged || tierChanged || bandChanged
                || rateChanged || effectiveFromChanged) {
            throw new LedgerPostingException(
                    "a commission rate is immutable; close it and insert a successor");
        }
    }
}
