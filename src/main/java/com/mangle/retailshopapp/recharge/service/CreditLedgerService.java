package com.mangle.retailshopapp.recharge.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mangle.retailshopapp.credit.model.RcCreditReq;
import com.mangle.retailshopapp.credit.repo.RcCreditReqRepository;
import com.mangle.retailshopapp.recharge.domain.Outstanding;
import com.mangle.retailshopapp.recharge.exception.CreditRequiresCustomerException;
import com.mangle.retailshopapp.recharge.exception.CustomerNotFound;
import com.mangle.retailshopapp.recharge.exception.InvalidTenderException;
import com.mangle.retailshopapp.recharge.repo.CreditBookRepository;

/**
 * The append-only credit tally (design-api.md §5.1).
 *
 * <p>⚠ THIS CLASS IS WHERE "A CREDIT ROW CANNOT EXIST WITHOUT AN IDENTIFIED,
 * NON-LEDGER CUSTOMER" BECOMES UNBYPASSABLE. §5.3 asks for the rule in TWO
 * layers on purpose: {@code SettlementPlan} catches the structural case and gives
 * a good message, and this service enforces it again at the lowest level so no
 * future caller can bypass it. The first makes it pleasant; the second makes it
 * true.
 *
 * <p><b>Append-only is what makes the settle transaction safe.</b> §4.3 step 7 is
 * an INSERT, never a read-modify-write of a stored balance, so a credit cannot be
 * lost or double-applied — and there is nothing to lock. §4.4 is explicit that a
 * pessimistic lock must NOT be added around the balance read: the figure shown at
 * basket time is advisory, there is no credit limit to enforce (FRD §8.7: "no
 * credit limit, purely manual, trust-based"), so there is nothing to
 * check-then-act on and no lost-update risk.
 *
 * <p>{@code DBT} is stored NEGATIVE and {@code DPAM} positive, matching seven
 * years of history exactly. {@link Outstanding} is the only place that convention
 * is turned into a number a screen can show.
 */
@Service
public class CreditLedgerService {

    /** A charge against the tab. Stored negative, as all 2,844 historical rows are. */
    private static final String TYPE_DEBIT = "DBT";

    /** A payment against the tab. Stored positive. */
    private static final String TYPE_PAYMENT = "DPAM";

    private final RcCreditReqRepository creditRequests;
    private final CreditBookRepository creditBook;
    private final CustomerDirectoryService customers;
    private final SettingService settings;

    public CreditLedgerService(RcCreditReqRepository creditRequests,
                               CreditBookRepository creditBook,
                               CustomerDirectoryService customers,
                               SettingService settings) {
        this.creditRequests = creditRequests;
        this.creditBook = creditBook;
        this.customers = customers;
        this.settings = settings;
    }

    /**
     * Appends a charge, attributed to the staff member who extended it.
     *
     * <p>⚠ {@code extendedBy} IS THE AUTHORISATION CONTROL, not metadata. §3.5:
     * requirement 9 means no per-row authorisation at all — all staff see all
     * transactions and any staff member may extend credit — so "the control is
     * the record: {@code extendedBy}, {@code extendedAt}, {@code note} taken from
     * {@code Principal}." Do not add a permission check here; add the record.
     *
     * @param customerId must resolve to a real, non-ledger customer
     * @throws CreditRequiresCustomerException 422 when it does not
     */
    @Transactional
    public RcCreditReq extendCredit(Integer customerId, BigDecimal amount, Integer extendedBy,
                                    String note, Integer txnId, LocalDateTime when) {
        if (customerId == null) {
            throw new CreditRequiresCustomerException();
        }
        if (amount == null || amount.signum() <= 0) {
            throw new CreditRequiresCustomerException(
                    "A credit line must be a positive amount; got " + amount + ".");
        }
        // The low-level enforcement §5.3 asks for. It throws CustomerNotFound for
        // a missing row or a ledger account; translated here so the caller sees
        // the rule that was broken rather than the lookup that failed.
        try {
            customers.requireCreditable(customerId);
        } catch (RuntimeException e) {
            throw new CreditRequiresCustomerException(
                    "Credit cannot be extended to customer " + customerId + ": " + e.getMessage());
        }

        RcCreditReq row = new RcCreditReq();
        row.setCustId(customerId);
        row.setCredDttm(when);
        row.setReqType(TYPE_DEBIT);
        // Negative, matching every historical DBT row. Outstanding is what turns
        // this back into "owes Rs 49" for a screen.
        row.setReqAmt(amount.negate());
        row.setCreBy(extendedBy);
        row.setNote(note);
        row.setTxnId(txnId);
        return creditRequests.save(row);
    }

    /**
     * Appends a payment against the tab.
     *
     * <p>Clears OLDEST FIRST across the whole tab, with no business picker and no
     * per-charge allocation (log §14.8) — which needs no code here, because a
     * single-sided tally consumed FIFO is what oldest-first MEANS. A surplus
     * simply leaves the balance positive and {@link Outstanding} reports it as an
     * advance held.
     *
     * <p>"Exceeds outstanding" is deliberately not refused (log §2.8): ₹120 owed,
     * the customer hands over ₹200, ₹80 is held against future trips, "decided by
     * arithmetic rather than by asking the staff member".
     */
    @Transactional
    public RcCreditReq receivePayment(Integer customerId, BigDecimal amount, Integer receivedBy,
                                      String note, Integer txnId, LocalDateTime when) {
        if (customerId == null) {
            throw new CreditRequiresCustomerException(
                    "A payment against a tab must name the customer whose tab it is.");
        }
        // ⚠ REVIEW FINDING 9. This method had NONE of extendCredit's guards, and
        // the tally being append-only is what made that serious: a null amount
        // hit a NOT NULL column, and a NEGATIVE one appended a DPAM row that
        // INCREASED the debt — a wrong row that cannot be corrected, only
        // offset by another row, because nothing here ever updates or deletes.
        //
        // The rule was already stated in the domain: Outstanding.receive refuses
        // both. Nothing on this path consulted it, which is the whole defect —
        // a rule that lives somewhere nothing reads is not a rule. It is stated
        // in two layers now, the same way §5.3 asks for the credit-customer
        // rule: here for a sentence the counter can show, and in
        // Outstanding.receive so no future caller can bypass it.
        if (amount == null || amount.signum() <= 0) {
            throw new InvalidTenderException(
                    "A payment against a tab must be a positive amount; got " + amount + ".");
        }
        customers.requireCreditable(customerId);

        // Consulting the rule rather than restating it. The result is discarded
        // on purpose: what is wanted is the refusal, and positionOf is the
        // caller's to ask for when it needs the number.
        positionOf(customerId).receive(amount);

        RcCreditReq row = new RcCreditReq();
        row.setCustId(customerId);
        row.setCredDttm(when);
        row.setReqType(TYPE_PAYMENT);
        row.setReqAmt(amount);
        row.setCreBy(receivedBy);
        row.setNote(note);
        row.setTxnId(txnId);
        return creditRequests.save(row);
    }

    /**
     * One customer's netted position.
     *
     * <p>Read inside the caller's transaction when it follows a write, so the
     * number the staff member sees is the number that was committed (§4.3 step 8).
     */
    @Transactional(readOnly = true)
    public Outstanding positionOf(int customerId) {
        var rows = creditBook.positionOf(customerId);
        if (rows.isEmpty()) {
            return Outstanding.clear();
        }
        Object[] row = rows.get(0);
        BigDecimal tally = row[0] == null ? BigDecimal.ZERO : (BigDecimal) row[0];
        LocalDateTime oldestOpen = row[1] == null ? null : ((java.sql.Timestamp) row[1]).toLocalDateTime();
        return Outstanding.fromTally(tally.setScale(2), oldestOpen);
    }

    /**
     * The credit book, oldest debt first (§3.2 endpoint #14).
     *
     * <p>⚠ CROSS-BUSINESS AND NEVER YEAR-FILTERED, and both are load-bearing.
     * log §14.8: someone owing ₹2,000 on water and ₹49 on recharge shows
     * ₹2,049, because a figure scoped to recharge would make the {@code warn}
     * flag — the entire reason this panel exists — understate the exposure while
     * looking authoritative. And §9.3 names what a year filter would cost: 33 of
     * 34 open balances vanish and staff stop chasing debt. The repository takes
     * no date parameter at all, which is the structural form of that rule.
     *
     * <p>The three bank pseudo-customers are excluded (§2.6): customer 269 alone
     * carries −₹46,766, which is a UPI clearing balance rather than a debt, and
     * listing a bank as the shop's largest debtor is how that figure would be
     * read.
     *
     * @param minOutstanding null for everyone with a tab; a positive amount
     *                       filters. Zero is NOT the same as null — see the
     *                       repository
     */
    @Transactional(readOnly = true)
    public List<CreditBookRow> creditBook(BigDecimal minOutstanding, Pageable pageable) {
        List<Integer> excluded = settings.ledgerCustomerIds();
        return creditBook.creditBook(excluded, minOutstanding, pageable).stream()
                .map(CreditLedgerService::toRow)
                .toList();
    }

    /** How many customers the credit book holds, for the pager. */
    @Transactional(readOnly = true)
    public long creditBookSize(BigDecimal minOutstanding) {
        return creditBook.countCreditBook(settings.ledgerCustomerIds(), minOutstanding);
    }

    /**
     * One person's tab, newest first, from every business (§3.2 endpoint #15).
     *
     * <p>The running balance is computed OLDEST-first and the rows returned
     * NEWEST-first, which is why the ordering appears twice in the query:
     * reversing a running total is not the same as computing it backwards.
     *
     * @throws CustomerNotFound 404 for an unknown customer or a ledger account —
     *         a ledger account has no tab to show, and showing one would present
     *         a clearing balance as somebody's debt
     */
    @Transactional(readOnly = true)
    public List<StatementLine> statement(int customerId, Pageable pageable) {
        customers.requireCreditable(customerId);
        return creditBook.statementOf(customerId, pageable).stream()
                .map(CreditLedgerService::toLine)
                .toList();
    }

    /** How many lines that statement has. */
    @Transactional(readOnly = true)
    public long statementSize(int customerId) {
        return creditBook.countStatement(customerId);
    }

    private static CreditBookRow toRow(Object[] r) {
        BigDecimal tally = r[4] == null ? BigDecimal.ZERO : (BigDecimal) r[4];
        return new CreditBookRow(
                ((Number) r[0]).intValue(),
                fullName((String) r[1], (String) r[2]),
                (String) r[3],
                // Outstanding is the only place the sign convention is read.
                Outstanding.fromTally(tally.setScale(2), timestamp(r[6])),
                timestamp(r[5]));
    }

    private static StatementLine toLine(Object[] r) {
        return new StatementLine(
                ((Number) r[0]).intValue(),
                timestamp(r[1]),
                (String) r[2],
                ((BigDecimal) r[3]).setScale(2),
                ((BigDecimal) r[4]).setScale(2),
                (String) r[5],
                (String) r[6],
                r[7] == null ? null : ((Number) r[7]).intValue());
    }

    private static LocalDateTime timestamp(Object value) {
        return value == null ? null : ((java.sql.Timestamp) value).toLocalDateTime();
    }

    /**
     * The two name columns joined, tolerating either being null.
     *
     * <p>{@code rs_cust_dtls} has rows with no last name, and "Asha null" on a
     * debtor list is the kind of thing that gets a screen distrusted.
     */
    private static String fullName(String first, String last) {
        String joined = ((first == null ? "" : first.trim()) + " "
                + (last == null ? "" : last.trim())).trim();
        return joined.isEmpty() ? null : joined;
    }

    /** One row of the credit book (#14). */
    public record CreditBookRow(int customerId, String name, String contactNumber,
                                Outstanding position, LocalDateTime lastActivityAt) {
    }

    /** One line of a statement (#15), from whichever business wrote it. */
    public record StatementLine(int creditRequestId, LocalDateTime at, String type,
                                BigDecimal amount, BigDecimal runningBalance, String note,
                                String extendedBy, Integer paymentId) {
    }

    /** The 30-day flag, computed server-side so two clients cannot disagree (§3.3). */
    @Transactional(readOnly = true)
    public boolean warns(Outstanding position, LocalDate today) {
        return position.warn(today, settings.getInt(SettingService.CREDIT_WARNING_DAYS, 30));
    }
}
