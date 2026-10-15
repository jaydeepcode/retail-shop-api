package com.mangle.retailshopapp.recharge.domain;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The commission arithmetic (design-ledger.md §3.3).
 *
 * <p>These are the assertions the slice is really about: a wrong rounding here
 * does not fail, it posts a voucher that is a few paise out and then needs a plug
 * figure to balance — and a plug figure in the book of record is
 * indistinguishable from an error.
 */
@DisplayName("FloatConsumption")
class FloatConsumptionTest {

    private static final BigDecimal WA_PARITY = BigDecimal.ONE.setScale(8);

    @Test
    @DisplayName("§3.3's pattern 2 example: Rs 199 at 2.9% earns Rs 5.77")
    void pattern2() {
        FloatConsumption c = FloatConsumption.of(
                new BigDecimal("199.00"), new BigDecimal("0.029"), WA_PARITY);

        assertEquals(new BigDecimal("193.23"), c.faceConsumed());
        assertEquals(new BigDecimal("193.23"), c.costConsumed());
        assertEquals(new BigDecimal("5.77"), c.commission());
        assertTrue(c.balances());
    }

    @Test
    @DisplayName("§3.3's pattern 1 example: bonus on topup, WA 0.97087379, earns Rs 5.80")
    void pattern1() {
        // r = 0 because the operator gives the margin as extra face at top-up
        // time rather than per transaction. The machinery is dormant today — all
        // four wallets are pattern 2 — but §3.5 says it must not be deleted, so
        // it must also not be untested.
        FloatConsumption c = FloatConsumption.of(
                new BigDecimal("199.00"), BigDecimal.ZERO, new BigDecimal("0.97087379"));

        assertEquals(new BigDecimal("199.00"), c.faceConsumed(),
                "r = 0, so the portal debits the full face");
        assertEquals(new BigDecimal("193.20"), c.costConsumed());
        assertEquals(new BigDecimal("5.80"), c.commission());
        assertTrue(c.balances());
    }

    @Test
    @DisplayName("§3.3's both-at-once example: Rs 11.40")
    void bothPatterns() {
        FloatConsumption c = FloatConsumption.of(
                new BigDecimal("199.00"), new BigDecimal("0.029"), new BigDecimal("0.97087379"));

        assertEquals(new BigDecimal("193.23"), c.faceConsumed());
        assertEquals(new BigDecimal("187.60"), c.costConsumed());
        assertEquals(new BigDecimal("11.40"), c.commission());
        assertTrue(c.balances());
    }

    @Test
    @DisplayName("the S3 brief's Dish TV example: Rs 240 at 3.6% earns Rs 8.64")
    void dishTvExample() {
        // next-session-s3.md's worked voucher, which is what the completion post
        // has to reproduce leg for leg.
        FloatConsumption c = FloatConsumption.atParity(
                new BigDecimal("240.00"), new BigDecimal("0.036"));

        assertEquals(new BigDecimal("231.36"), c.faceConsumed(),
                "240 x (1 - 0.036) — this is the Cr FLOAT_A1TOPUP leg");
        assertEquals(new BigDecimal("8.64"), c.commission(),
                "240 - 231.36 — the Cr INC_RCHG_COMM leg, operator-earned");
        assertTrue(c.balances());
    }

    @Test
    @DisplayName("commission by subtraction is NOT A x r rounded separately")
    void subtractionNotMultiplication() {
        // The case that shows why the rule exists. A x r on these inputs rounds
        // one way and A - round(A x (1-r)) the other, and the difference is the
        // paisa that would stop the voucher balancing.
        BigDecimal amount = new BigDecimal("147.00");
        BigDecimal rate = new BigDecimal("0.035");

        FloatConsumption c = FloatConsumption.atParity(amount, rate);

        BigDecimal naive = amount.multiply(rate).setScale(2, java.math.RoundingMode.HALF_UP);
        assertEquals(new BigDecimal("5.15"), naive, "A x r rounds to 5.15");
        assertEquals(new BigDecimal("5.14"), c.commission(),
                "but 147.00 - 141.86 is 5.14, and that is the figure that balances");
        assertTrue(c.balances());
        assertTrue(naive.compareTo(c.commission()) != 0,
                "the two genuinely differ — this is not a tautological test");
    }

    @Test
    @DisplayName("the identity collected = cost + commission holds across the amount range")
    void identityHolds() {
        // Every amount the presets offer, plus the two commonest TV amounts, at
        // each of the seeded rates. If any of these failed, V23's trigger 1 would
        // refuse the income voucher at the counter.
        String[] amounts = {"26.00", "123.00", "141.00", "147.00", "179.00", "199.00",
                            "219.00", "239.00", "299.00", "349.00", "200.00", "240.00"};
        String[] rates = {"0.00", "0.025", "0.029", "0.035", "0.036", "0.04"};

        for (String a : amounts) {
            for (String r : rates) {
                FloatConsumption c = FloatConsumption.atParity(new BigDecimal(a), new BigDecimal(r));
                assertTrue(c.balances(),
                        "A=" + a + " r=" + r + " did not balance: " + c.collected()
                                + " vs " + c.costConsumed() + " + " + c.commission());
                assertTrue(c.commission().signum() >= 0,
                        "commission went negative at A=" + a + " r=" + r);
            }
        }
    }

    @Test
    @DisplayName("a zero rate earns nothing and consumes the whole face")
    void zeroRateAtParity() {
        // Worth stating: at parity with r = 0 the shop earns nothing, which is
        // exactly why log §8.2 refuses the income post on a MISSING rate rather
        // than defaulting to zero. A zero that was meant is fine; a zero that
        // stood in for "unknown" understates commission AND the float decrement.
        FloatConsumption c = FloatConsumption.atParity(new BigDecimal("199.00"), BigDecimal.ZERO);

        assertEquals(new BigDecimal("199.00"), c.faceConsumed());
        assertEquals(new BigDecimal("0.00"), c.commission());
        assertTrue(c.balances());
    }

    @Test
    @DisplayName("nonsense inputs are refused rather than posted")
    void refusals() {
        assertThrows(IllegalArgumentException.class, () ->
                FloatConsumption.atParity(BigDecimal.ZERO, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () ->
                FloatConsumption.atParity(new BigDecimal("-1.00"), BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () ->
                FloatConsumption.atParity(new BigDecimal("199.00"), new BigDecimal("-0.01")));
        assertThrows(IllegalArgumentException.class, () ->
                FloatConsumption.atParity(new BigDecimal("199.00"), BigDecimal.ONE),
                "a rate of 1 would mean the operator funds the whole recharge");
        assertThrows(IllegalArgumentException.class, () ->
                FloatConsumption.of(new BigDecimal("199.00"), BigDecimal.ZERO, BigDecimal.ZERO));
    }
}
