package com.mangle.retailshopapp.recharge.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mangle.retailshopapp.recharge.domain.ServiceType;
import com.mangle.retailshopapp.recharge.exception.UnknownOperatorException;
import com.mangle.retailshopapp.recharge.exception.UnknownRouteException;
import com.mangle.retailshopapp.recharge.model.RcOperator;
import com.mangle.retailshopapp.recharge.model.RcOperatorPreset;
import com.mangle.retailshopapp.recharge.model.RefRechargeRoute;
import com.mangle.retailshopapp.recharge.repo.RcOperatorPresetRepository;
import com.mangle.retailshopapp.recharge.repo.RcOperatorRepository;
import com.mangle.retailshopapp.recharge.repo.RefRechargeRouteRepository;

/**
 * What the counter may offer, and what it defaults to (design-api.md §5.1).
 *
 * <p>Decides: which operators and routes are live, each operator's default route,
 * each operator's amount chips, and the surcharge a service type starts with.
 * Decides nothing about money.
 *
 * <p><b>No cache, which differs from §5.1's "short-TTL cache".</b> The catalogue
 * is 14 operator rows, 3 routes and 12 presets, read once per counter startup
 * through one endpoint. A TTL cache would add an invalidation question — these
 * tables are edited by hand in SQL by design (requirement 10), so a cached
 * catalogue means a hand-edit does not take effect until it expires — in exchange
 * for saving three indexed reads. Recorded as a deliberate difference.
 */
@Service
public class OperatorConfigService {

    private final RcOperatorRepository operators;
    private final RcOperatorPresetRepository presets;
    private final RefRechargeRouteRepository routes;
    private final SettingService settings;

    public OperatorConfigService(RcOperatorRepository operators,
                                 RcOperatorPresetRepository presets,
                                 RefRechargeRouteRepository routes,
                                 SettingService settings) {
        this.operators = operators;
        this.presets = presets;
        this.routes = routes;
        this.settings = settings;
    }

    /**
     * The operators the counter may offer.
     *
     * <p>⚠ EXCLUDES THE TWO SENTINELS as well as the inactive rows.
     * {@code MOBL_UNKNOWN} and {@code TVOPR_UNKNOWN} exist so V15's backfill has
     * somewhere to put 9,155 rows with no usable operator; they are seeded
     * inactive, and {@link RcOperator#isSelectable()} is belt and braces.
     *
     * <p>And it excludes {@code IDEA} and {@code VDFN}, which V20 retired into
     * {@code VI} — the counter must offer Vi, not Idea and Vodafone (log §15.2).
     * They stay in the table because older rows foreign-key them.
     */
    @Transactional(readOnly = true)
    public List<RcOperator> selectableOperators() {
        return operators.findByActiveTrueOrderByRechargeKindAscSortOrderAsc().stream()
                .filter(RcOperator::isSelectable)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RcOperator> selectableOperators(ServiceType serviceType) {
        return operators.findByRechargeKindAndActiveTrueOrderBySortOrderAsc(serviceType).stream()
                .filter(RcOperator::isSelectable)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RefRechargeRoute> activeRoutes() {
        return routes.findByActiveTrueOrderBySortOrderAsc();
    }

    /**
     * @throws UnknownOperatorException 404 — including for a retired code, which
     *         is the point: a client still offering {@code IDEA} is told so rather
     *         than booking a sale against a float wallet that no longer exists
     */
    @Transactional(readOnly = true)
    public RcOperator requireSelectable(String operatorCode) {
        return operators.findById(operatorCode)
                .filter(RcOperator::isSelectable)
                .orElseThrow(() -> new UnknownOperatorException(operatorCode));
    }

    /**
     * An operator by code, live or retired.
     *
     * <p>⚠ FOR A RECHARGE THAT HAS ALREADY BEEN SOLD, never for the picker.
     * {@link #requireSelectable} applies the picker's rule — active, not a
     * sentinel — and applying that to a completion traps money: a recharge
     * settled before its operator was retired could never be completed, and its
     * cash would sit in {@code RCHG_PENDING} with no exit but a refund that this
     * slice has not built. V20 retiring {@code IDEA} and {@code VDFN} is not a
     * hypothetical; V16 will do the same to a route.
     *
     * <p>{@code RcOperator}'s own javadoc states the principle: "{@code IS_ACTIVE}
     * governs the picker, not referential integrity."
     */
    @Transactional(readOnly = true)
    public RcOperator requireKnown(String operatorCode) {
        return operators.findById(operatorCode)
                .orElseThrow(() -> new UnknownOperatorException(operatorCode));
    }

    /** A route by code, live or retired — see {@link #requireKnown}. */
    @Transactional(readOnly = true)
    public RefRechargeRoute requireKnownRoute(String routeCode) {
        return routes.findById(routeCode)
                .orElseThrow(() -> new UnknownRouteException(routeCode));
    }

    @Transactional(readOnly = true)
    public RefRechargeRoute requireActiveRoute(String routeCode) {
        return routes.findById(routeCode)
                .filter(RefRechargeRoute::isActive)
                .orElseThrow(() -> new UnknownRouteException(routeCode));
    }

    /**
     * The route a new recharge gets when the client does not choose one.
     *
     * <p>⚠ PER OPERATOR, NEVER GLOBALLY {@code DIRECT} (log §14.6). BSNL plus all
     * five TV operators have no direct connection — 382 of 1,037 FY recharges, 37%
     * — and a global {@code DIRECT} default would send every one of them into
     * §8.2's rate refusal, because no such rate row is seeded.
     *
     * <p>Config-driven, never computed from history: §5.3 warns that the TV
     * default contradicts the history 7,899:9 and "must not be corrected".
     */
    @Transactional(readOnly = true)
    public String defaultRouteFor(String operatorCode) {
        return requireSelectable(operatorCode).getDefaultRouteCode();
    }

    /**
     * The amount chips for one operator, commonest first.
     *
     * <p>An EMPTY list is a correct answer, not missing data (log §14.10): TV
     * operators have none, because Dish TV's commonest amount is 16% of its volume
     * against mobile's 78% — so the screen shows a ±₹10 stepper instead of chips.
     * {@code VI} has none either, which is the one figure V28's header flags for a
     * second look.
     */
    @Transactional(readOnly = true)
    public List<BigDecimal> presetsFor(String operatorCode) {
        return presets.findActiveFor(operatorCode).stream()
                .map(RcOperatorPreset::amount)
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, List<BigDecimal>> allPresets() {
        return presets.findAllActive().stream().collect(Collectors.groupingBy(
                RcOperatorPreset::operatorCode,
                Collectors.mapping(RcOperatorPreset::amount, Collectors.toList())));
    }

    /**
     * The surcharge a line starts with, before staff touch it.
     *
     * <p>⚠ DTH ON, MOBILE OFF, AND EDITABLE EITHER WAY (log §15.11). This
     * NARROWS what §10.2 and §14.3 recorded, deliberately: §10.2 found the
     * surcharge "not strictly DTH — 198 headers carry it on a mobile-only basket"
     * and quoted the owner as "on Recharge or any DTH associated recharge". Going
     * forward the DEFAULT is DTH only. Those 198 mobile cases stay as history and
     * are not a defect, and because the field is editable a mobile line can still
     * carry one — nothing is made impossible, only the default changes.
     */
    @Transactional(readOnly = true)
    public BigDecimal defaultSurchargeFor(ServiceType serviceType) {
        return serviceType.surchargeOnByDefault()
                ? settings.getDecimal(SettingService.SURCHARGE_DEFAULT_TV, new BigDecimal("10.00"))
                : settings.getDecimal(SettingService.SURCHARGE_DEFAULT_MOBILE,
                        new BigDecimal("0.00"));
    }

    /** Below this, change is not displayed at the counter (log §14.5). */
    @Transactional(readOnly = true)
    public BigDecimal changeDisplayThreshold() {
        return settings.getDecimal(SettingService.CHANGE_DISPLAY_THRESHOLD,
                new BigDecimal("5.00"));
    }

    /** The 30-day credit-ageing threshold. */
    @Transactional(readOnly = true)
    public int creditWarningDays() {
        return settings.getInt(SettingService.CREDIT_WARNING_DAYS, 30);
    }
}
