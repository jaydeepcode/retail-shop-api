package com.mangle.retailshopapp.recharge.model.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * One startup call (design-api.md §3.2 endpoint #1).
 *
 * <p>Everything the counter needs before the first keystroke, so the entry screen
 * makes no further request per operator.
 */
public record CounterConfigResponse(
        List<OperatorConfig> operators,
        List<RouteConfig> routes,
        int creditWarningDays,
        int fyStartMonth,
        String currentFinancialYear,
        BigDecimal changeDisplayThreshold,
        BigDecimal surchargeDefaultTv,
        BigDecimal surchargeDefaultMobile) {

    /**
     * One operator, with its default route and its amount chips.
     *
     * <p>⚠ {@code presets} MAY BE EMPTY, AND EMPTY IS AN ANSWER (log §14.10). TV
     * operators have none — Dish TV's commonest amount is 16% of its volume
     * against mobile's 78%, so the screen shows a ±₹10 stepper instead of chips.
     * {@code VI} has none either, which V28's header flags for a second look. A
     * client must render a bare amount field rather than wait for rows.
     */
    public record OperatorConfig(
            String code,
            String name,
            String serviceType,
            String defaultRouteCode,
            List<BigDecimal> presets) {
    }

    public record RouteConfig(String code, String name) {
    }
}
