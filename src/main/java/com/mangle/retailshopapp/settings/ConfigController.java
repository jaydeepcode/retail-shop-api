package com.mangle.retailshopapp.settings;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mangle.retailshopapp.recharge.service.FinancialYears;

/**
 * {@code GET /config} — the shop-wide config, for every screen in every section
 * (next-session-s5.md item 3, settled 2026-11-02).
 *
 * <p>⚠ <b>IT IS A PATH THE SPA SHELL'S {@code permitAll} ALREADY MATCHED.</b>
 * {@code SecurityConfig} permits {@code "/*"} for {@code index.html} and the
 * built assets, and Ant's {@code /*} matches exactly one path segment — so
 * {@code /config} fell inside it and was reachable with no token at all, ahead of
 * any matcher added later, because matchers are evaluated in order. The fix is a
 * {@code /config} matcher placed ABOVE that line rather than below it; the test
 * is {@code ConfigControllerTest.unauthenticatedIsRefused}, which was watched to
 * fail with the matcher moved back down. Nothing here is secret, but a water
 * {@code ROLE_CUSTOMER} token has no business reading the shop's config and §3's
 * point is that <b>the default must be deny</b>.
 *
 * <p>In {@code settings/} beside {@link SettingService} rather than under a
 * {@code controller/} subpackage: item 3 settles the service's move "beside a new
 * {@code ConfigController}", and a two-class package reads better flat than split
 * in two. {@code accounting/controller/} is a subpackage because that module has
 * four other kinds of class.
 *
 * <p>⚠ <b>{@link FinancialYears} IS IMPORTED FROM {@code recharge/service}, AND
 * THAT IS A DEPENDENCY POINTING THE WRONG WAY</b> — the same objection that moved
 * {@code ErrorCode} to {@code apierror} and {@code SettingService} to here. It is
 * left pointing that way on purpose. Moving it is a third mechanical rename, and
 * item 2 settled that the churn belongs in its own commit with nothing else in
 * it — S4's lesson was that a mechanical move inside a feature commit makes the
 * feature unreviewable. The class is a four-line wrapper over
 * {@code rc_setting['fy.start.month']} plus a Spring-free value object, so the
 * move is cheap whenever someone takes it: {@code FinancialYears} has three other
 * callers, all in {@code recharge}.
 */
@RestController
@RequestMapping("/config")
@Validated
public class ConfigController {

    private final SettingService settings;
    private final FinancialYears financialYears;

    public ConfigController(SettingService settings, FinancialYears financialYears) {
        this.settings = settings;
        this.financialYears = financialYears;
    }

    /**
     * Everything shop-wide a screen needs before its first keystroke.
     *
     * <p>Read per call rather than cached, for the reason {@link SettingService}
     * gives: eight rows read a handful of times is not a performance problem, and
     * a cache would mean an edit made in SQL — which is how this config is edited
     * by design — did not take effect until a restart.
     */
    @GetMapping
    public ShopConfigResponse config() {
        return new ShopConfigResponse(
                settings.changeDisplayThreshold(),
                settings.floatImpsFee(),
                financialYears.startMonth(),
                financialYears.current().label());
    }
}
