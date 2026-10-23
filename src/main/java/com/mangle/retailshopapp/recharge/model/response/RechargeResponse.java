package com.mangle.retailshopapp.recharge.model.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.mangle.retailshopapp.recharge.domain.FailureResolution;
import com.mangle.retailshopapp.recharge.domain.RechargeStatus;
import com.mangle.retailshopapp.recharge.domain.ServiceType;
import com.mangle.retailshopapp.recharge.model.RcOperator;
import com.mangle.retailshopapp.recharge.model.RcRecharge;
import com.mangle.retailshopapp.recharge.model.RefRechargeRoute;

/**
 * One recharge as the counter sees it (design-api.md §3.3).
 *
 * <p>⚠ {@code status} AND {@code resolution} ARE BOTH DERIVED FROM ONE COLUMN.
 * §3.3's table presents them as two fields and the schema has one — V9 seeds
 * {@code RETRIED} and {@code REFUNDED} as statuses with {@code IS_TERMINAL=TRUE}.
 * So a retried failure surfaces here as {@code status=FAILED,
 * resolution=RETRIED}. See {@code FailureResolution} for why this is derived
 * rather than stored.
 *
 * <p>⚠ NO {@code version} FIELD, which §3.3 shows. There is no
 * {@code VERSION} column: the concurrency guard is the
 * {@code PESSIMISTIC_WRITE} design-domain.md §5.4 specifies and log §8.1 relies
 * on. Sending a version the server does not maintain would invite a client to
 * build optimistic-concurrency handling around a number that never changes.
 *
 * <p>Every operator and route carries BOTH its code and a human name (§6.1), so
 * the UI never has to know that {@code RJIO} means Jio or that {@code SRVTEB} is
 * a route in disguise.
 */
public record RechargeResponse(
        int rechargeId,
        String reference,
        ServiceType serviceType,
        String number,
        String operatorCode,
        String operatorName,
        String routeCode,
        String routeName,
        boolean routeConfirmed,
        BigDecimal amount,
        BigDecimal surcharge,
        RechargeStatus status,
        FailureResolution resolution,
        String vendorRef,
        Integer retryOfRechargeId,
        String failureReason,
        LocalDateTime rechargeAt,
        LocalDateTime statusChangedAt) {

    public static RechargeResponse from(RcRecharge r, RcOperator operator, RefRechargeRoute route) {
        return new RechargeResponse(
                r.getRechargeId(),
                "R-" + r.getRechargeId(),
                r.getRechargeKind(),
                r.getTargetNumber(),
                r.getOperatorCode(),
                operator == null ? r.getOperatorCode() : operator.getDisplayName(),
                r.getRouteCode(),
                route == null ? r.getRouteCode() : route.getDescription(),
                // The route is confirmed once someone has actually done the
                // recharge, which is the moment it stops being a default.
                r.getStatusCode() != RechargeStatus.AWAITING_RECHARGE,
                r.getAmount(),
                r.getSurcharge(),
                FailureResolution.wireStatus(r.getStatusCode()),
                FailureResolution.of(r.getStatusCode()),
                r.getExternalRef(),
                r.getFailedRechargeId(),
                r.getFailureReason(),
                r.getRechargeDttm(),
                r.getStatusChangedAt());
    }
}
