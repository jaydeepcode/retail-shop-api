package com.mangle.retailshopapp.accounting;

/**
 * A draft that the posting rules refuse.
 *
 * <p>These are the checks that duplicate what V22's {@code CHECK}s and V23's
 * triggers enforce independently. The duplication is deliberate and §1.3 of
 * design-ledger-api.md gives the reason: the edge check exists to return a
 * sentence rather than a raw {@code SIGNAL SQLSTATE '45000'}. The database
 * remains the authority — it refuses the same writes on every path, including
 * hand-SQL.
 */
public class LedgerPostingException extends RuntimeException {

    public LedgerPostingException(String message) {
        super(message);
    }
}
