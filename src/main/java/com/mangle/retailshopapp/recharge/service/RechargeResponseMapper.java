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

import com.mangle.retailshopapp.customer.model.CustomerDetails;
import com.mangle.retailshopapp.recharge.domain.FinancialYear;
import com.mangle.retailshopapp.recharge.domain.SettlementPlan;
import com.mangle.retailshopapp.recharge.model.RcOperator;
import com.mangle.retailshopapp.recharge.model.RcRecharge;
import com.mangle.retailshopapp.recharge.model.RefRechargeRoute;
import com.mangle.retailshopapp.recharge.model.response.CreditSummaryResponse;
import com.mangle.retailshopapp.recharge.model.response.RechargeResponse;
import com.mangle.retailshopapp.recharge.model.response.RechargeWorkItemResponse;
import com.mangle.retailshopapp.recharge.model.response.SettlementLineResponse;
import com.mangle.retailshopapp.recharge.model.response.TransactionResponse;
import com.mangle.retailshopapp.recharge.repo.RcOperatorRepository;
import com.mangle.retailshopapp.recharge.repo.RefRechargeRouteRepository;

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

    public RechargeResponseMapper(RcOperatorRepository operators,
                                  RefRechargeRouteRepository routes,
                                  OperatorConfigService config,
                                  FinancialYears financialYears) {
        this.operators = operators;
        this.routes = routes;
        this.config = config;
        this.financialYears = financialYears;
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
        for (SettlementPlan.SettlementLine line : plan.settlementLines()) {
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
