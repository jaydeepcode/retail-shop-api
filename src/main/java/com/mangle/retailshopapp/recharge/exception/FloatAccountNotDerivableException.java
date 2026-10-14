package com.mangle.retailshopapp.recharge.exception;

import java.util.Map;

/**
 * 422 — this operator and route do not resolve to a float wallet the shop holds.
 *
 * <p>The shop holds FOUR wallets — {@code FLOAT_A1TOPUP}, {@code FLOAT_ARTL},
 * {@code FLOAT_RJIO}, {@code FLOAT_VI} — and the account is derived at runtime
 * from the route and the operator together, with no mapping table (log §15.1):
 *
 * <pre>
 * route = 'A1TOPUP'  ->  FLOAT_A1TOPUP
 * route = 'DIRECT'   ->  CONCAT('FLOAT_', OPERATOR_CODE)
 * </pre>
 *
 * <p>Two things reach this. A route with no rule at all — {@code SARAVATE}, which
 * is seeded active only because V15's backfill needs it to exist. And a
 * {@code DIRECT} pairing for an operator that has no direct connection, such as
 * BSNL or any TV operator (§2.4:86), where the derivation names an account that
 * was never created.
 *
 * <p>⚠ A 422 RATHER THAN AN {@code IllegalStateException}, which is what this
 * was. The old form fell through to the catch-all handler, so the counter got
 * {@code 500 INTERNAL_ERROR / "An unexpected error occurred."} and the carefully
 * written explanation existed only in the server log — on the one failure where
 * the staff member can actually act, by picking the right route.
 *
 * <p>As with {@link NoCommissionRateException}, the sale still stands: the cash
 * was posted at settlement and the amount sits in {@code RCHG_PENDING} until the
 * completion succeeds.
 */
public class FloatAccountNotDerivableException extends RechargeRuleViolationException {

    public FloatAccountNotDerivableException(String operatorCode, String routeCode,
                                             String derivedAccount) {
        super(ErrorCode.FLOAT_ACCOUNT_NOT_DERIVABLE, message(operatorCode, routeCode, derivedAccount),
                Map.of("operatorCode", operatorCode, "routeCode", routeCode,
                        "derivedAccount", derivedAccount == null ? "" : derivedAccount));
    }

    private static String message(String operatorCode, String routeCode, String derivedAccount) {
        if (derivedAccount == null) {
            return "The %s route has no float wallet, so %s cannot be completed on it — "
                    .formatted(routeCode, operatorCode)
                    + "choose Direct or A1 Topup.";
        }
        return "%s has no %s connection, so there is no %s wallet to draw from — "
                .formatted(operatorCode, routeCode, derivedAccount)
                + "complete it on A1 Topup instead.";
    }
}
