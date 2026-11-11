package com.mangle.retailshopapp.accounting;

import com.mangle.retailshopapp.apierror.ErrorCode;
import com.mangle.retailshopapp.apierror.RechargeRuleViolationException;

/**
 * A draft that the posting rules refuse — a 422 carrying its sentence.
 *
 * <p>These are the checks that duplicate what V22's {@code CHECK}s and V23's
 * triggers enforce independently. The duplication is deliberate and §1.3 of
 * design-ledger-api.md gives the reason: the edge check exists to return a
 * sentence rather than a raw {@code SIGNAL SQLSTATE '45000'}. The database
 * remains the authority — it refuses the same writes on every path, including
 * hand-SQL.
 *
 * <p>⚠ IT DID NOT RETURN THAT SENTENCE UNTIL S5, AND NOTHING SAID SO. It
 * extended {@code RuntimeException} with no {@code ErrorCode}, so a refused
 * draft fell past every handler in {@code RechargeExceptionHandler} to the
 * catch-all at its end and came back <b>500 {@code INTERNAL_ERROR}, "An
 * unexpected error occurred."</b> — the message logged against a trace id and
 * discarded on the way out. The javadoc above described an intent the class did
 * not implement.
 *
 * <p>It survived because nothing reached it: {@code RechargeLedgerPostings} only
 * ever posts drafts the rules already accept, so no caller had composed a draft
 * that could be refused. S5 is the first slice where a human composes one —
 * endpoints 2, 7, 8, 9 and 19 all do — which is why it is fixed before them
 * rather than after the first bare 500 in production.
 *
 * <p>Adding the new controllers to {@code assignableTypes} does NOT fix this and
 * that is the trap worth naming: the advice IS reached, and what was missing was
 * a handler for this type. The two failures look identical on the wire and have
 * nothing to do with each other (next-session-s5.md items 4 and 5).
 *
 * <p>⚠ This import is why the {@code apierror} rename came first. The tree is
 * shared vocabulary — §8.4's "these names are a contract" holds across the whole
 * surface — and {@code accounting} depending on {@code recharge.exception} would
 * have been a module dependency pointing the wrong way to get at it.
 */
public class LedgerPostingException extends RechargeRuleViolationException {

    /**
     * The ordinary refusal: a malformed, unbalanced or ineligible draft.
     *
     * <p>Single-argument on purpose, so the ~18 existing throw sites across
     * {@link LedgerPostingService} and {@link LedgerCorrectionRules} keep saying
     * what they already say. Their sentences are the specific part, and they were
     * already written to be read by a person.
     */
    public LedgerPostingException(String message) {
        this(ErrorCode.LEDGER_POSTING_REFUSED, message);
    }

    /** For the refusal whose code a client has to branch on. */
    public LedgerPostingException(ErrorCode code, String message) {
        super(code, message);
    }
}
