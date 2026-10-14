package com.mangle.retailshopapp.recharge.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mangle.retailshopapp.credit.model.RcTxnHeader;
import com.mangle.retailshopapp.credit.repo.RcTxnHeaderRepository;
import com.mangle.retailshopapp.recharge.domain.FinancialYear;
import com.mangle.retailshopapp.recharge.domain.RechargeStatus;
import com.mangle.retailshopapp.recharge.domain.SettlementMethod;
import com.mangle.retailshopapp.recharge.exception.PaymentNotFound;
import com.mangle.retailshopapp.recharge.model.RcRecharge;
import com.mangle.retailshopapp.recharge.model.RcTxnPayment;
import com.mangle.retailshopapp.recharge.repo.AccAccountBalanceRepository;
import com.mangle.retailshopapp.recharge.repo.RcRechargeRepository;
import com.mangle.retailshopapp.recharge.repo.RcTxnPaymentRepository;
import com.mangle.retailshopapp.recharge.repo.RechargeHistoryRepository;
import com.mangle.retailshopapp.recharge.repo.RechargeOpenItemsRepository;

/**
 * History, the day summary, and reading one visit back (design-api.md §5.1).
 *
 * <p>⚠ HISTORY IS NEW SALES ONLY (log §15.11). The owner settled this on
 * 2026-10-12 — "only new sales is fine for recharge data" — which closes §11
 * question 5 the easy way: no ongoing {@code UNION} with
 * {@code rc_txn_details}, no legacy rows, and V15's backfill stays a one-off
 * migration serving the cutover rather than a permanent read path.
 *
 * <p>The consequence belongs on the screen, not in a comment: {@code rc_recharge}
 * is empty until V15 runs, so history is <b>genuinely blank on day one</b> and
 * fills only as new sales are taken. That is correct, not a bug — but an empty
 * screen with no explanation reads as broken, so {@link #dataAvailableFrom()}
 * exists to let the client say which it is.
 */
@Service
public class RechargeHistoryService {

    /** The statuses that are still someone's problem (§7.1(c)). */
    private static final List<RechargeStatus> OPEN_STATUSES = List.of(
            RechargeStatus.AWAITING_RECHARGE, RechargeStatus.FAILED);

    private static final String RCHG_PENDING = "RCHG_PENDING";
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final RechargeHistoryRepository history;
    private final RechargeOpenItemsRepository openItems;
    private final RcRechargeRepository recharges;
    private final RcTxnHeaderRepository headers;
    private final RcTxnPaymentRepository payments;
    private final AccAccountBalanceRepository balances;
    private final FinancialYears financialYears;
    private final Clock clock;

    public RechargeHistoryService(RechargeHistoryRepository history,
                                  RechargeOpenItemsRepository openItems,
                                  RcRechargeRepository recharges,
                                  RcTxnHeaderRepository headers,
                                  RcTxnPaymentRepository payments,
                                  AccAccountBalanceRepository balances,
                                  FinancialYears financialYears,
                                  Clock clock) {
        this.history = history;
        this.openItems = openItems;
        this.recharges = recharges;
        this.headers = headers;
        this.payments = payments;
        this.balances = balances;
        this.financialYears = financialYears;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // #7 — one visit
    // ------------------------------------------------------------------

    /**
     * The whole visit: the payment, its recharges and its tenders.
     *
     * <p>Not financial-year scoped, and it must not be: a payment id is an exact
     * reference, and refusing to show last year's visit because the picker is on
     * this year would be the "stored mode" failure §9.3 forbids.
     *
     * @throws PaymentNotFound 404
     */
    @Transactional(readOnly = true)
    public Visit visit(int paymentId) {
        RcTxnHeader header = headers.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFound(paymentId));

        List<RcRecharge> lines = recharges.findByTxnIdOrderBySeqNoAsc(paymentId);
        List<RcTxnPayment> tenders = payments.findByTxnId(paymentId);

        return new Visit(header, lines, tenders,
                financialYears.of(header.getTxnDttm().toLocalDate()));
    }

    // ------------------------------------------------------------------
    // #17 — history search
    // ------------------------------------------------------------------

    /**
     * History search (§3.2 endpoint #17, §7.1(a)).
     *
     * <p>⚠ THE NUMBER MATCH IS A PREFIX. {@code LIKE '%1234%'} cannot use
     * {@code idx_rech_number} and degrades to a full scan; §7.1(a) notes that at
     * 52k rows that is still only ~50ms, so a contains mode is AFFORDABLE — "but
     * it must be a deliberate, separate, slower mode, not the default." This is
     * the default, so it is a prefix.
     *
     * <p>The resolved year is echoed back with its bounds so the UI never
     * computes financial-year boundaries itself and the two cannot drift (§3.6).
     */
    @Transactional(readOnly = true)
    public HistoryPage search(String financialYearLabel, String numberPrefix, String operatorCode,
                              String routeCode, RechargeStatus status, Integer staffId,
                              String externalRef, Pageable pageable) {
        FinancialYear fy = financialYears.resolve(financialYearLabel);

        Page<RcRecharge> page = history.search(
                fy.startInclusive(), fy.endExclusive(),
                blankToNull(operatorCode), blankToNull(routeCode), status, staffId,
                blankToNull(numberPrefix), blankToNull(externalRef), pageable);

        return new HistoryPage(fy, page);
    }

    /**
     * The years that actually hold data, for the picker (§9.2).
     *
     * <p>Derived from two cheap aggregates rather than stored. Returns an empty
     * list when {@code rc_recharge} is empty, which is day one — and that is the
     * signal the screen needs to explain itself rather than show a bare "no
     * results".
     */
    @Transactional(readOnly = true)
    public List<String> dataAvailableFrom() {
        List<Object[]> bounds = history.dataDateBounds();
        if (bounds.isEmpty() || bounds.get(0)[0] == null) {
            return List.of();
        }
        LocalDateTime min = (LocalDateTime) bounds.get(0)[0];
        LocalDateTime max = (LocalDateTime) bounds.get(0)[1];

        List<String> labels = new ArrayList<>();
        FinancialYear fy = financialYears.of(max.toLocalDate());
        FinancialYear earliest = financialYears.of(min.toLocalDate());
        while (fy.startYear() >= earliest.startYear()) {
            labels.add(fy.label());
            fy = fy.previous();
        }
        return labels;
    }

    // ------------------------------------------------------------------
    // #18 — the day summary, which is what the slice's gate reads
    // ------------------------------------------------------------------

    /**
     * End of day (§3.2 endpoint #18, §7.1(c)).
     *
     * <p>⚠ THIS IS THE FIGURE THE S3 GATE COMPARES TO THE DRAWER
     * (implementation-plan.md:125), so the two rules below are not presentation
     * choices.
     *
     * <p><b>⚠ THE IDENTITY, AND A CORRECTION WORTH READING BEFORE TRUSTING AN
     * EARLIER ONE.</b> The drawer reconciles like this:
     *
     * <pre>
     * drawer  =  totalTakings  +  totalSurcharge  +  heldPending
     * </pre>
     *
     * <p>This slice's own notes, PR and plan all stated it as
     * {@code drawer = totalTakings + heldPending}, omitting the surcharge. That
     * is wrong on any day carrying a DTH sale, which is most days: a ₹240 Dish TV
     * recharge with the ₹10 fee puts ₹250 in the drawer while {@code totalTakings}
     * reports ₹240, because takings are the recharge FACE VALUE and the surcharge
     * is deliberately its own income line (log §10.2, §15.11). A close screen
     * built on the two-term form shows a phantom shortfall of ₹10 per DTH
     * recharge — and FY2026-27 carried ₹2,957 of surcharge, so it would be wrong
     * every day.
     *
     * <p>It was found by driving a real sale through a running server, not by any
     * test: the unit test asserting the identity used two MOBILE recharges, where
     * the surcharge term is zero and the wrong formula happens to balance. Two
     * correct tests with an unasserted gap between them.
     *
     * <p>{@code totalCollected} is the defence rather than the fix: it carries
     * {@code totalTakings + totalSurcharge} so a client has one number to compare
     * and no arithmetic to get wrong.
     *
     * <p><b>Takings count COMPLETED recharges only</b> (requirement 6,
     * design-domain.md §5.2). That is also what makes {@code VOIDED} work: a
     * voided sale drops out for free, because the query already filters on the
     * status.
     *
     * <p><b>And a consequence worth stating on the screen rather than hiding:</b>
     * because takings count only completed recharges while the tender mix counts
     * everything settled, CASH IN THE DRAWER WILL NOT EQUAL TAKINGS whenever a
     * recharge has failed and not yet been refunded. §7.1(c) is explicit that this
     * is correct — the money is held pending — and that the warning should read
     * "₹X held against N unresolved recharges", not "till mismatch".
     *
     * <p>⚠ THE ₹X COMES FROM THE LEDGER, NOT FROM A STATUS JOIN. Under log §7.2
     * collected cash sits in {@code RCHG_PENDING} until completion, so open
     * exposure is one account balance — "the figure becomes reconcilable instead
     * of merely displayed". The COUNT still comes from {@code rc_recharge},
     * because an account balance cannot say how many recharges are behind it.
     */
    @Transactional(readOnly = true)
    public DaySummary daySummary(LocalDate date) {
        LocalDate day = date == null ? LocalDate.now(clock) : date;
        LocalDateTime start = day.atStartOfDay();
        LocalDateTime end = day.plusDays(1).atStartOfDay();

        List<TakingsRow> takings = history.takingsByOperatorAndRoute(start, end).stream()
                .map(r -> new TakingsRow(
                        (String) r[0], (String) r[1],
                        ((Number) r[2]).longValue(),
                        scale2(r[3]), scale2(r[4])))
                .toList();

        Map<SettlementMethod, BigDecimal> tenderMix = new java.util.EnumMap<>(SettlementMethod.class);
        for (Object[] row : payments.tenderMixOn(start, end)) {
            tenderMix.put(SettlementMethod.fromLineType((String) row[0]), scale2(row[1]));
        }

        // The two warning counts (requirement 10). Day-scoped, because this is
        // the day's own exposure — the worklist is where the whole open set lives,
        // and §7.1(d) keeps that query free of any date at all.
        long awaiting = 0;
        long failed = 0;
        for (Object[] row : openItems.countOpenByStatus(OPEN_STATUSES)) {
            RechargeStatus status = (RechargeStatus) row[0];
            long count = ((Number) row[1]).longValue();
            if (status == RechargeStatus.AWAITING_RECHARGE) {
                awaiting = count;
            } else if (status == RechargeStatus.FAILED) {
                failed = count;
            }
        }

        BigDecimal totalTakings = takings.stream()
                .map(TakingsRow::amount).reduce(ZERO, BigDecimal::add);
        BigDecimal totalSurcharge = takings.stream()
                .map(TakingsRow::surcharge).reduce(ZERO, BigDecimal::add);

        return new DaySummary(
                day,
                takings,
                totalTakings,
                totalSurcharge,
                // What the customer actually handed over for the completed
                // recharges. Derived here rather than by the client, because
                // getting it wrong is exactly what happened — see the javadoc.
                totalTakings.add(totalSurcharge),
                tenderMix,
                awaiting,
                failed,
                heldPending());
    }

    /**
     * What the shop is holding against in-flight and unresolved recharges.
     *
     * <p>Read from {@code acc_account_balance}, never recomputed here — see
     * {@link AccAccountBalanceRepository} for the defect that makes a second
     * implementation of this arithmetic a bad idea.
     *
     * <p>Returns zero when the account has no postings, which is every account
     * before the first sale.
     */
    @Transactional(readOnly = true)
    public BigDecimal heldPending() {
        return balances.balanceOf(RCHG_PENDING).stream()
                .findFirst()
                .map(RechargeHistoryService::scale2)
                .orElse(ZERO);
    }

    // ------------------------------------------------------------------

    /** One visit, materialised inside the transaction. */
    public record Visit(RcTxnHeader header, List<RcRecharge> recharges,
                        List<RcTxnPayment> tenders, FinancialYear financialYear) {
    }

    /** A page of history, with the year it resolved to. */
    public record HistoryPage(FinancialYear financialYear, Page<RcRecharge> page) {
    }

    /** Takings for one operator-and-route pair. */
    public record TakingsRow(String operatorCode, String routeCode, long count,
                             BigDecimal amount, BigDecimal surcharge) {
    }

    /**
     * The end-of-day read.
     *
     * <p>{@code totalCollected} exists so the close screen has ONE number to
     * compare against the drawer, and the reason is a defect this record caused.
     * See {@link #daySummary(LocalDate)} for the identity and what went wrong.
     */
    public record DaySummary(LocalDate date, List<TakingsRow> takings,
                             BigDecimal totalTakings, BigDecimal totalSurcharge,
                             BigDecimal totalCollected,
                             Map<SettlementMethod, BigDecimal> tenderMix,
                             long awaitingCount, long unresolvedFailureCount,
                             BigDecimal heldPending) {
    }

    private static BigDecimal scale2(Object value) {
        if (value == null) {
            return ZERO;
        }
        return ((BigDecimal) value).setScale(2, java.math.RoundingMode.UNNECESSARY);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
