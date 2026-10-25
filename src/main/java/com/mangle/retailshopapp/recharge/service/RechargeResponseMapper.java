package com.mangle.retailshopapp.recharge.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.mangle.retailshopapp.credit.model.RcTxnHeader;
import com.mangle.retailshopapp.customer.model.CustomerDetails;
import com.mangle.retailshopapp.customer.repo.CustomerDetailsRepository;
import com.mangle.retailshopapp.recharge.domain.FinancialYear;
import com.mangle.retailshopapp.recharge.domain.Outstanding;
import com.mangle.retailshopapp.recharge.domain.SettlementPlan;
import com.mangle.retailshopapp.recharge.model.RcOperator;
import com.mangle.retailshopapp.recharge.model.RcRecharge;
import com.mangle.retailshopapp.recharge.model.RcTxnPayment;
import com.mangle.retailshopapp.recharge.model.RefRechargeRoute;
import com.mangle.retailshopapp.recharge.model.response.CreditSummaryResponse;
import com.mangle.retailshopapp.recharge.model.response.RechargeResponse;
import com.mangle.retailshopapp.recharge.model.response.RechargeWorkItemResponse;
import com.mangle.retailshopapp.recharge.model.response.RefundResponse;
import com.mangle.retailshopapp.recharge.model.response.RetryRechargeResponse;
import com.mangle.retailshopapp.recharge.model.response.SettlementLineResponse;
import com.mangle.retailshopapp.recharge.model.response.TransactionResponse;
import com.mangle.retailshopapp.recharge.repo.RcOperatorRepository;
import com.mangle.retailshopapp.recharge.repo.RefRechargeRouteRepository;
import com.mangle.retailshopapp.settlement.SettlementLine;
import com.mangle.retailshopapp.settlement.SettlementMethod;
import com.mangle.retailshopapp.user.model.User;
import com.mangle.retailshopapp.user.repo.UserRepository;

/**
 * Hand-written mapping (design-api.md §6.1).
 *
 * <p>No MapStruct and no ModelMapper: "two dozen small mappings do not justify a
 * dependency, an annotation processor and a build step", and the codebase already
 * maps by hand.
 *
 * <p>A separate component rather than static factories on the records, because
 * most of these need two or three sources — a recharge plus its operator plus its
 * route — and §6.1 allows exactly that: "or a package-private
 * {@code RechargeMapper} where a mapping needs two sources."
 *
 * <p>Mapping runs INSIDE the transactional service method but is not itself the
 * boundary (§6.1), so responses are fully materialised before the connection is
 * released. The catalogue lookups here are the reason: they would be lazy-loading
 * failures outside it.
 */
@Component
public class RechargeResponseMapper {

    private final RcOperatorRepository operators;
    private final RefRechargeRouteRepository routes;
    private final OperatorConfigService config;
    private final FinancialYears financialYears;
    private final CustomerDetailsRepository customers;
    private final CreditLedgerService credit;
    private final UserRepository users;

    public RechargeResponseMapper(RcOperatorRepository operators,
                                  RefRechargeRouteRepository routes,
                                  OperatorConfigService config,
                                  FinancialYears financialYears,
                                  CustomerDetailsRepository customers,
                                  CreditLedgerService credit,
                                  UserRepository users) {
        this.operators = operators;
        this.routes = routes;
        this.config = config;
        this.financialYears = financialYears;
        this.customers = customers;
        this.credit = credit;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public RechargeResponse toRecharge(RcRecharge r) {
        return RechargeResponse.from(r,
                operators.findById(r.getOperatorCode()).orElse(null),
                routes.findById(r.getRouteCode()).orElse(null));
    }

    @Transactional(readOnly = true)
    public List<RechargeResponse> toRecharges(List<RcRecharge> list) {
        // One catalogue read for the whole list rather than one per row: a basket
        // is small, but the worklist page is not, and N+1 over a 14-row table is
        // still N+1.
        Map<String, RcOperator> operatorsByCode = operators.findAll().stream()
                .collect(Collectors.toMap(RcOperator::getOperatorCode, Function.identity()));
        Map<String, RefRechargeRoute> routesByCode = routes.findAll().stream()
                .collect(Collectors.toMap(RefRechargeRoute::getCode, Function.identity()));

        return list.stream()
                .map(r -> RechargeResponse.from(r,
                        operatorsByCode.get(r.getOperatorCode()),
                        routesByCode.get(r.getRouteCode())))
                .toList();
    }

    @Transactional(readOnly = true)
    public RechargeWorkItemResponse toWorkItem(RcRecharge r, Map<String, RcOperator> byCode) {
        RcOperator operator = byCode.get(r.getOperatorCode());
        return new RechargeWorkItemResponse(
                r.getRechargeId(),
                "R-" + r.getRechargeId(),
                r.getRechargeKind(),
                r.getTargetNumber(),
                r.getOperatorCode(),
                operator == null ? r.getOperatorCode() : operator.getDisplayName(),
                r.getRouteCode(),
                r.getAmount(),
                r.getSurcharge(),
                r.getRechargeDttm(),
                r.getTxnId(),
                r.getFailureReason());
    }

    @Transactional(readOnly = true)
    public Map<String, RcOperator> operatorsByCode() {
        return operators.findAll().stream()
                .collect(Collectors.toMap(RcOperator::getOperatorCode, Function.identity()));
    }

    /**
     * One visit read back from the database (#7).
     *
     * <p>⚠ THE TENDER AND THE CHANGE COME FROM THE HEADER, not from the tender
     * rows. {@code rc_txn_payment} has no tendered/change pair — the tender
     * belongs to the BASKET, because there is one drawer and one hand-over
     * whatever the basket holds — so {@code amt_tndred} and {@code amt_returned}
     * are what reconstruct the cash line's figures. A reader of the settle
     * response reasonably expects columns that do not exist.
     *
     * <p>⚠ AND THE FIGURES ARE THE RECORDED ONES, not recomputed. A settle
     * response reports what {@code SettlementPlan} just calculated; this reports
     * what was actually written seven months ago, under whatever the rules were
     * then. Recomputing would quietly re-price history the first time a setting
     * changed — the same hazard §4.1 names for commission rates.
     */
    @Transactional(readOnly = true)
    public TransactionResponse toVisit(RechargeHistoryService.Visit visit, String staffUsername,
                                       LocalDate today) {
        RcTxnHeader header = visit.header();
        BigDecimal threshold = config.changeDisplayThreshold();
        BigDecimal changeGiven = header.getAmtReturned();

        CustomerDetails creditCustomer = header.getCustId() == null
                ? null
                : customers.findById(header.getCustId().longValue()).orElse(null);

        List<SettlementLineResponse> lines = new ArrayList<>(visit.tenders().size());
        BigDecimal totalSurcharge = BigDecimal.ZERO.setScale(2);
        for (RcRecharge r : visit.recharges()) {
            totalSurcharge = totalSurcharge.add(r.getSurcharge());
        }

        for (RcTxnPayment tender : visit.tenders()) {
            SettlementMethod method = SettlementMethod.fromLineType(tender.getLineType());
            boolean isCash = method.carriesTender();
            lines.add(new SettlementLineResponse(
                    tender.getId().getSeqNo(),
                    method,
                    tender.getAmount(),
                    isCash ? header.getAmtTndred() : null,
                    isCash ? changeGiven : null,
                    isCash && changeGiven.signum() > 0
                            && changeGiven.compareTo(threshold) >= 0,
                    tender.getReferenceNo(),
                    header.getCustId(),
                    creditCustomer == null ? null : creditCustomer.getCustomerName(),
                    // Who extended the credit, read from the row rather than from
                    // the caller: this is a historical fact, and §3.5 makes that
                    // record the whole authorisation control.
                    tender.getExtendedBy() == null ? null
                            : users.findById(tender.getExtendedBy().longValue())
                                    .map(User::getUsername).orElse(null),
                    tender.getNote()));
        }

        CreditSummaryResponse creditSummary = null;
        if (header.getCustId() != null) {
            creditSummary = CreditSummaryResponse.from(
                    header.getCustId(),
                    creditCustomer == null ? null : creditCustomer.getCustomerName(),
                    // What this visit put on the tab, not the whole balance.
                    visit.tenders().stream()
                            .filter(t -> SettlementMethod.CREDIT.lineType().equals(t.getLineType()))
                            .map(RcTxnPayment::getAmount)
                            .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add),
                    credit.positionOf(header.getCustId()),
                    today,
                    config.creditWarningDays());
        }

        return new TransactionResponse(
                header.getTxnId(),
                "P-" + header.getTxnId(),
                header.getTxnDttm(),
                staffUsername,
                financialYears.of(header.getTxnDttm().toLocalDate()).label(),
                header.getTxnTotalAmt(),
                totalSurcharge,
                header.getAmtTndred(),
                changeGiven,
                toRecharges(visit.recharges()),
                lines,
                creditSummary);
    }

    /**
     * The retry response (#12).
     *
     * <p>Both recharges are mapped, so the counter can drop the original off the
     * failures list and put the new one on the worklist from one payload.
     */
    @Transactional(readOnly = true)
    public RetryRechargeResponse toRetry(RechargeFailureResolutionService.Retried retried,
                                         CustomerDetails customer, LocalDate today) {
        return new RetryRechargeResponse(
                toRecharge(retried.retry()),
                toRecharge(retried.original()),
                retried.plan().differenceOwed(),
                retried.differenceSettledAs(),
                retried.plan().topUpDue(),
                retried.topUpTakenAs(),
                creditSummary(customer, retried.creditPosition(), today));
    }

    /** The refund response (#13). */
    @Transactional(readOnly = true)
    public RefundResponse toRefund(RechargeFailureResolutionService.Refunded refunded,
                                   CustomerDetails customer, LocalDate today) {
        return new RefundResponse(
                toRecharge(refunded.original()),
                refunded.amount(),
                refunded.mode(),
                creditSummary(customer, refunded.creditPosition(), today));
    }

    /**
     * The tab after the resolution, or null when no tab was touched.
     *
     * <p>Null is the ordinary answer: a cash refund names no customer at all,
     * which is the point of defaulting to handing the cash back (log §7.4).
     * {@code amountExtended} is null here rather than zero — nothing was
     * extended, and a zero would read as "credit of ₹0 was given".
     */
    private CreditSummaryResponse creditSummary(CustomerDetails customer, Outstanding position,
                                                LocalDate today) {
        if (position == null) {
            return null;
        }
        return CreditSummaryResponse.from(
                customer == null ? null : customer.getCustId(),
                customer == null ? null : customer.getCustomerName(),
                null,
                position,
                today,
                config.creditWarningDays());
    }

    /**
     * The settle response.
     *
     * <p>{@code changeDisplayed} is computed here from
     * {@code rc_setting['change.display.threshold']} so the counter does not hold
     * the rule (log §14.5) — and {@code changeDue} is still reported, because the
     * evening close needs the figure even on the sales where the rupee is not
     * handed over.
     */
    @Transactional(readOnly = true)
    public TransactionResponse toTransaction(RechargeSettlementService.Settled settled,
                                             String staffUsername,
                                             CustomerDetails creditCustomer,
                                             LocalDate today) {
        SettlementPlan plan = settled.plan();
        BigDecimal threshold = config.changeDisplayThreshold();
        FinancialYear fy = financialYears.of(settled.takenAt().toLocalDate());

        List<SettlementLineResponse> lines = new ArrayList<>(plan.settlementLines().size());
        int lineId = 1;
        for (SettlementLine line : plan.settlementLines()) {
            boolean isCash = line.method().carriesTender();
            lines.add(new SettlementLineResponse(
                    lineId++,
                    line.method(),
                    line.amount(),
                    isCash ? plan.cashTendered() : null,
                    isCash ? plan.changeDue() : null,
                    isCash && plan.changeIsDisplayed(threshold),
                    line.reference(),
                    line.customerId(),
                    creditCustomer == null ? null : creditCustomer.getCustomerName(),
                    line.customerId() == null ? null : staffUsername,
                    line.note()));
        }

        CreditSummaryResponse credit = null;
        if (plan.hasCredit() && settled.creditPosition() != null) {
            credit = CreditSummaryResponse.from(
                    plan.creditLine().customerId(),
                    creditCustomer == null ? null : creditCustomer.getCustomerName(),
                    plan.creditLine().amount(),
                    settled.creditPosition(),
                    today,
                    config.creditWarningDays());
        }

        return new TransactionResponse(
                settled.paymentId(),
                "P-" + settled.paymentId(),
                settled.takenAt(),
                staffUsername,
                fy.label(),
                plan.totalDue(),
                plan.totalSurcharge(),
                plan.cashTendered(),
                plan.changeDue(),
                toRecharges(settled.recharges()),
                lines,
                credit);
    }
}
