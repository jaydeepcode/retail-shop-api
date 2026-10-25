package com.mangle.retailshopapp.recharge.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mangle.retailshopapp.credit.model.RcCreditReq;
import com.mangle.retailshopapp.credit.model.RcTxnDetail;
import com.mangle.retailshopapp.credit.model.RcTxnHeader;
import com.mangle.retailshopapp.credit.repo.RcTxnDetailRepository;
import com.mangle.retailshopapp.credit.repo.RcTxnHeaderRepository;
import com.mangle.retailshopapp.recharge.domain.Outstanding;
import com.mangle.retailshopapp.recharge.domain.RechargeStatus;
import com.mangle.retailshopapp.recharge.domain.ServiceType;
import com.mangle.retailshopapp.recharge.domain.SettlementPlan;
import com.mangle.retailshopapp.recharge.model.RcIdempotency;
import com.mangle.retailshopapp.recharge.model.RcRecharge;
import com.mangle.retailshopapp.recharge.model.RcTxnPayment;
import com.mangle.retailshopapp.recharge.model.RcTxnPaymentId;
import com.mangle.retailshopapp.recharge.repo.RcDishtvDtlsRepository;
import com.mangle.retailshopapp.recharge.repo.RcIdempotencyRepository;
import com.mangle.retailshopapp.recharge.repo.RcRechargeRepository;
import com.mangle.retailshopapp.recharge.repo.RcTxnPaymentRepository;
import com.mangle.retailshopapp.settlement.SettlementLine;
import com.mangle.retailshopapp.settlement.SettlementMethod;

/**
 * The one atomic write (design-api.md §4.3, §5.1).
 *
 * <p>One {@code @Transactional} method creates the payment, N recharges (each
 * {@code AWAITING_RECHARGE}), M settlement lines, at most one credit row, and the
 * cash-leg voucher. Requirement 2 asks for exactly that atomicity.
 *
 * <p><b>Ordered so the cheapest rejections happen before any write</b> (§4.3):
 *
 * <ol>
 *   <li>pure validation, no I/O — delegated to {@link SettlementPlan};
 *   <li>resolve references — operators, routes, the credit customer;
 *   <li>insert the idempotency row FIRST;
 *   <li>insert the payment header;
 *   <li>insert the N item lines and recharge rows;
 *   <li>insert the M settlement lines;
 *   <li>append the credit row, if there is one;
 *   <li>read the resulting outstanding, inside the same transaction;
 *   <li>post the cash leg to the ledger, inside the same transaction.
 * </ol>
 *
 * <p><b>Partial failure: there is no partial state.</b> Any exception rolls back
 * every insert INCLUDING the idempotency row, so the client may safely resend
 * with the same key. That is why step 3 comes before any money is written — "a
 * duplicate key here aborts the whole transaction before any money is written,
 * which is the correct outcome."
 *
 * <p><b>⚠ There is no server-side basket.</b> §3.2 states it as a design decision
 * so it is not "fixed" later by accident: the basket lives in the client's memory
 * until the staff member settles it, and the server never holds a draft. That
 * removes orphaned baskets, basket expiry, two terminals editing one basket and a
 * "clear basket" endpoint, at the cost of nothing the requirements ask for.
 *
 * <p>Step 9 is what makes this slice S2's first real caller. The posting is
 * INSIDE this transaction, enforced by {@code MANDATORY} rather than remembered.
 */
@Service
public class RechargeSettlementService {

    /** What a recharge item line is flagged as in {@code rc_txn_details}. */
    private static final String ITEM_FLAG_MOBILE = "MOBL";
    private static final String ITEM_FLAG_TV = "TV";

    private final RcTxnHeaderRepository headers;
    private final RcTxnDetailRepository details;
    private final RcRechargeRepository recharges;
    private final RcTxnPaymentRepository payments;
    private final RcIdempotencyRepository idempotency;
    private final RcDishtvDtlsRepository connections;
    private final OperatorConfigService config;
    private final CustomerDirectoryService customers;
    private final CreditLedgerService credit;
    private final RechargeLedgerPostings ledger;
    private final Clock clock;

    public RechargeSettlementService(RcTxnHeaderRepository headers,
                                     RcTxnDetailRepository details,
                                     RcRechargeRepository recharges,
                                     RcTxnPaymentRepository payments,
                                     RcIdempotencyRepository idempotency,
                                     RcDishtvDtlsRepository connections,
                                     OperatorConfigService config,
                                     CustomerDirectoryService customers,
                                     CreditLedgerService credit,
                                     RechargeLedgerPostings ledger,
                                     Clock clock) {
        this.headers = headers;
        this.details = details;
        this.recharges = recharges;
        this.payments = payments;
        this.idempotency = idempotency;
        this.connections = connections;
        this.config = config;
        this.customers = customers;
        this.credit = credit;
        this.ledger = ledger;
        this.clock = clock;
    }

    /**
     * Settles one basket.
     *
     * <p>What the client does NOT send, and therefore cannot disagree with: the
     * total, the change, the status, and the route. §3.3 — "the server fills each
     * recharge with its operator's configured default and #9 confirms it. A
     * client may not set it here, so a route cannot be chosen before anyone knows
     * which connection was used."
     *
     * @param idempotencyKey required; the client-generated UUID from the header
     */
    @Transactional
    public Settled settle(List<RequestedRecharge> requested, List<RequestedTender> tenders,
                          Integer staffUserId, String idempotencyKey) {
        LocalDateTime now = LocalDateTime.now(clock);

        // --- Step 1 and 2: validate, then resolve. -----------------------
        // Resolution happens before the plan is built, because the plan needs
        // each line's defaulted route and surcharge — and both come from config.
        List<SettlementPlan.RechargeLine> rechargeLines = new ArrayList<>(requested.size());
        for (RequestedRecharge r : requested) {
            var operator = config.requireSelectable(r.operatorCode());
            // The route is NOT taken from the request. It is defaulted here and
            // overridable at completion (log §14.6), because at the moment the
            // customer pays nobody yet knows which connection will be used.
            String routeCode = operator.getDefaultRouteCode();

            // An absent surcharge takes the configured default for the service
            // type — Rs 10 on TV, zero on mobile. An explicit zero is honoured,
            // which is why this tests for null rather than for a zero amount:
            // staff clearing the fee on a DTH line must stick.
            BigDecimal surcharge = r.surcharge() != null
                    ? r.surcharge()
                    : config.defaultSurchargeFor(r.serviceType());

            rechargeLines.add(new SettlementPlan.RechargeLine(
                    r.serviceType(), r.number(), r.operatorCode(), routeCode,
                    r.amount(), surcharge));
        }

        List<SettlementLine> tenderLines = tenders.stream()
                .map(t -> new SettlementLine(t.method(), t.amount(),
                        t.amountTendered(), t.reference(), t.customerId(), t.note()))
                .toList();

        // Throws 422 before anything is written. The total is recomputed here and
        // nowhere else.
        SettlementPlan plan = SettlementPlan.of(rechargeLines, tenderLines);

        if (plan.hasCredit()) {
            // Resolved now rather than at step 7, so an unknown or ledger-account
            // customer costs no writes at all.
            customers.requireCreditable(plan.creditLine().customerId());
        }

        // --- Step 3: the idempotency row, before any money. --------------
        RcIdempotency key = new RcIdempotency();
        key.setKeyValue(idempotencyKey);
        key.setEndpoint("POST /recharge/transactions");
        key.setCreBy(staffUserId);
        key.setCreDttm(now);
        idempotency.saveAndFlush(key);

        // --- Step 4: the payment header. ---------------------------------
        RcTxnHeader header = new RcTxnHeader();
        header.setTxnDttm(now);
        header.setTxnTotalAmt(plan.totalDue());
        // ⚠ amt_tndred and amt_returned are the BASKET's tender and change, and
        // both are >= 0 here. That is the V13/V14 semantics, not the legacy
        // app's: the legacy UPI encoding is amt_tndred = 0 / amt_returned =
        // -amount, which V14's CHECK (amt_returned >= 0) will reject. New sales
        // are written the new way from the start, which is what lets V14 land on
        // cutover day without touching anything this module wrote.
        header.setAmtTndred(plan.cashTendered());
        header.setAmtReturned(plan.changeDue());
        header.setStatusCode("SETTLED");
        header.setCreBy(staffUserId);
        // The named customer a credit tender requires (§5.3). Null on an
        // anonymous cash or UPI sale, which is most of them.
        header.setCustId(plan.hasCredit() ? plan.creditLine().customerId() : null);
        headers.saveAndFlush(header);

        int paymentId = header.getTxnId();
        key.setPaymentId(paymentId);

        // --- Step 5: the item lines and the recharges. -------------------
        List<RcRecharge> created = new ArrayList<>(plan.recharges().size());
        for (SettlementPlan.RechargeLine line : plan.recharges()) {
            // rc_recharge foreign-keys rc_txn_details on (TXN_ID, SEQ_NO), so the
            // item line comes first and the recharge points at it.
            RcTxnDetail item = new RcTxnDetail();
            item.setRcTxnHeader(header);
            item.setTxnFlg(line.serviceType() == ServiceType.TV ? ITEM_FLAG_TV : ITEM_FLAG_MOBILE);
            item.setAmount(line.lineTotal());
            item.setReferenceNo(line.number());
            item.setRefCompany(line.operatorCode());
            details.saveAndFlush(item);

            RcRecharge recharge = new RcRecharge();
            recharge.setRechargeKind(line.serviceType());
            recharge.setOperatorCode(line.operatorCode());
            recharge.setRouteCode(line.routeCode());
            recharge.setTargetNumber(line.number());
            recharge.setDishtvNo(knownConnection(line));
            recharge.setAmount(line.amount());
            recharge.setSurcharge(line.surcharge());
            recharge.setRechargeDttm(now);
            recharge.setStatusCode(RechargeStatus.AWAITING_RECHARGE);
            recharge.setTxnId(paymentId);
            recharge.setSeqNo(item.getSeqNo());
            recharge.setCreBy(staffUserId);
            recharge.setCreDttm(now);
            created.add(recharges.saveAndFlush(recharge));
        }

        // --- Step 6 and 7: the tenders, and the credit row. --------------
        RcCreditReq creditRow = null;
        if (plan.hasCredit()) {
            var creditLine = plan.creditLine();
            creditRow = credit.extendCredit(creditLine.customerId(), creditLine.amount(),
                    staffUserId, creditLine.note(), paymentId, now);
        }

        short seqNo = 1;
        for (SettlementLine line : plan.settlementLines()) {
            RcTxnPayment tender = new RcTxnPayment();
            tender.setId(new RcTxnPaymentId(paymentId, seqNo++));
            tender.setLineType(line.method().lineType());
            tender.setAmount(line.amount());
            tender.setReferenceNo(line.reference());
            tender.setCreDttm(now);
            if (line.method() == SettlementMethod.CREDIT) {
                // ck_txnpay_credit enforces that a CRDT line carries its
                // credit-book row, so this cannot be left unset.
                tender.setCreReqId(creditRow.getId());
                tender.setExtendedBy(staffUserId);
                tender.setNote(line.note());
            }
            payments.saveAndFlush(tender);
        }

        // --- Step 8: the balance, inside the same transaction. -----------
        // §4.3 step 8: "so the number the staff sees is the number that was
        // committed."
        Outstanding position = plan.hasCredit()
                ? credit.positionOf(plan.creditLine().customerId())
                : null;

        // --- Step 9: the cash leg. ---------------------------------------
        // Inside this transaction, which MANDATORY enforces rather than trusts.
        // Stage two posts at completion (log §7.2).
        ledger.postSettlement(paymentId, plan, staffUserId, now);

        return new Settled(paymentId, plan, created, position, now);
    }

    /**
     * The known TV connection this number IS, or null (§4.6).
     *
     * <p>⚠ ADVISORY, NEVER A REJECTION. design-domain.md §4.6: "staff still pick
     * a CONNECTION; the customer link is advisory." 1,279 of 1,583 distinct
     * recharged TV numbers match no row in the 458-row table (§R3), so a miss is
     * the ordinary case and leaves the column NULL — the box number is in
     * {@code TARGET_NUMBER} either way, and §R3 and V18 are both explicit that
     * connection rows are not to be fabricated for the unknown ones.
     *
     * <p>The match is the same rule V15's backfill applies to history: the
     * TRIMMED typed number must exist in {@code rc_dishtv_dtls}. A mobile line
     * never gets one whatever its digits are, which is what
     * {@code ck_rech_tv CHECK (DISHTV_NO IS NULL OR RECHARGE_KIND = 'TV')}
     * enforces in the database.
     */
    private String knownConnection(SettlementPlan.RechargeLine line) {
        if (line.serviceType() != ServiceType.TV) {
            return null;
        }
        String number = line.number().trim();
        return connections.existsById(number) ? number : null;
    }

    /**
     * Whether this key has already been used, and what it produced.
     *
     * <p>⚠ MUST BE CALLED IN A SEPARATE TRANSACTION. §7.1 spells out the
     * subtlety: a duplicate key throws and the whole transaction rolls back
     * INCLUDING the key row, so the stored result is not visible from inside the
     * aborted transaction. The handler catches that specific case and looks the
     * key up in a fresh read-only transaction.
     *
     * <p>A pre-check {@code SELECT} before inserting would be a race in itself.
     * Insert-then-recover is correct and needs no locking.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<Integer> replayedPaymentId(String idempotencyKey) {
        return idempotency.findByKeyValue(idempotencyKey).map(RcIdempotency::getPaymentId);
    }

    /** What the client asked for, before the server filled in what it owns. */
    public record RequestedRecharge(ServiceType serviceType, String number, String operatorCode,
                                    BigDecimal amount, BigDecimal surcharge) {
    }

    /** One tender as the client sent it. */
    public record RequestedTender(SettlementMethod method, BigDecimal amount,
                                  BigDecimal amountTendered, String reference,
                                  Integer customerId, String note) {
    }

    /** Everything the response needs, materialised inside the transaction. */
    public record Settled(int paymentId, SettlementPlan plan, List<RcRecharge> recharges,
                          Outstanding creditPosition, LocalDateTime takenAt) {
    }
}
