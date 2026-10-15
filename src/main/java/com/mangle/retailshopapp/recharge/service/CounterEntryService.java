package com.mangle.retailshopapp.recharge.service;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mangle.retailshopapp.recharge.domain.ServiceType;
import com.mangle.retailshopapp.recharge.model.RcDishtvDtls;
import com.mangle.retailshopapp.recharge.model.RcRecharge;
import com.mangle.retailshopapp.recharge.repo.RcDishtvDtlsRepository;
import com.mangle.retailshopapp.recharge.repo.RcRechargeRepository;

/**
 * The entry-step reads (design-api.md §5.1).
 *
 * <p>Operator and route prefill from a number's last recharge, and TV connection
 * search. <b>Decides nothing about money</b> — §5.1 says so explicitly, and it is
 * worth holding to: every figure this service returns is a suggestion the staff
 * member can overrule, so none of it may ever become an input the settle path
 * trusts.
 */
@Service
public class CounterEntryService {

    private final RcRechargeRepository recharges;
    private final RcDishtvDtlsRepository connections;
    private final OperatorConfigService config;

    public CounterEntryService(RcRechargeRepository recharges,
                               RcDishtvDtlsRepository connections,
                               OperatorConfigService config) {
        this.recharges = recharges;
        this.connections = connections;
        this.config = config;
    }

    /**
     * What this number was last recharged with (§3.2 endpoint #2).
     *
     * <p>⚠ IT EARNS ITS PLACE ON MEASUREMENT, which is why it exists at all
     * (log §14.10): 88.5% of mobile and 93.6% of TV recharges go to a number seen
     * before, and the operator differs from that number's last recharge on only
     * 8.8% of mobile repeats and 3.4% of TV — after folding the {@code SRVTE*}
     * codes to their operator, which halves the apparent rate from 18.1%.
     *
     * <p>⚠ AND IT WILL RETURN {@code known = false} FOR EVERY NUMBER UNTIL V15
     * RUNS. It reads {@code rc_recharge} alone, which is empty until the cutover
     * backfill — the same consequence log §15.11 records for the history screen.
     * The counter must treat an unknown number as ordinary rather than as a
     * failure, because on day one every number is unknown.
     *
     * <p>Not FY-filtered and deliberately unbounded by date: a number recharged
     * once a year is still a number seen before, and this is not a history view.
     */
    @Transactional(readOnly = true)
    public NumberHint hintFor(ServiceType serviceType, String number) {
        Optional<RcRecharge> last = recharges
                .findFirstByTargetNumberAndRechargeKindOrderByRechargeDttmDesc(number, serviceType);

        if (last.isEmpty()) {
            return NumberHint.unknown();
        }
        RcRecharge r = last.get();

        // ⚠ The operator is re-validated rather than echoed. A number last
        // recharged on IDEA would otherwise prefill a code V20 retired, and the
        // staff member would only find out at the settle. Suggesting nothing is
        // better than suggesting something the server will refuse.
        String operatorCode = config.selectableOperators(serviceType).stream()
                .map(o -> o.getOperatorCode())
                .filter(code -> code.equals(r.getOperatorCode()))
                .findFirst()
                .orElse(null);

        return new NumberHint(
                true,
                operatorCode,
                // The route is the operator's CURRENT default, not the one the
                // last recharge used: log §14.6 makes the route a completion-time
                // decision, so prefilling a historical route would be asserting
                // something nobody knows yet.
                operatorCode == null ? null : config.defaultRouteFor(operatorCode),
                r.getAmount(),
                r.getRechargeDttm(),
                r.getDishtvNo());
    }

    /**
     * TV connection search by name, contact number or connection number
     * (§3.2 endpoint #3).
     *
     * <p>458 rows, and §12's over-engineering register is explicit that no index
     * or search engine is warranted for a table this size — a contains match over
     * 458 rows is faster than the round trip that carries it.
     *
     * <p>This is also the one place name search genuinely works. §7.1(a) warns it
     * cannot be a primary filter over RECHARGES — 9,776 distinct recharged mobile
     * numbers against 244 matching a customer means most recharges have no name
     * at all — but a TV connection carries one by definition.
     */
    @Transactional(readOnly = true)
    public List<RcDishtvDtls> searchConnections(String query, int limit) {
        if (query == null || query.trim().length() < 2) {
            // §3.2 sets a two-character minimum. Returning empty rather than
            // throwing: a staff member mid-keystroke has not made a mistake.
            return List.of();
        }
        return connections.search(query.trim(), PageRequest.of(0, limit));
    }

    /**
     * The prefill, or {@code known = false} with nulls (§3.2 endpoint #2).
     *
     * <p>A flag rather than a null response, because "I have not seen this number"
     * and "I could not answer" are different things, and a 404 would make the
     * commonest case on day one look like an error.
     */
    public record NumberHint(
            boolean known,
            String operatorCode,
            String routeCode,
            java.math.BigDecimal lastAmount,
            java.time.LocalDateTime lastRechargeAt,
            String dishtvNo) {

        static NumberHint unknown() {
            return new NumberHint(false, null, null, null, null, null);
        }
    }
}
