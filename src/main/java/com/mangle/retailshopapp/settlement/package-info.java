/**
 * How a sale was paid for, for any business that takes money.
 *
 * <p>Extracted from {@code recharge.domain.SettlementPlan} for S4. That class
 * validated the tender and the basket together, and only the tender half is
 * reusable: {@code SettlementPlan.of(List<RechargeLine>, …)} takes recharge
 * lines carrying a {@code surcharge} and derives the basket total from them,
 * while a ticket's total is {@code fareAmount + commissionAmount}
 * (next-session-s4.md correction 2).
 *
 * <p>So the split is:
 *
 * <table>
 *   <caption>what is shared and what is not</caption>
 *   <tr><th>Here — the tender half</th><th>The business's own — the basket half</th></tr>
 *   <tr><td>lines sum exactly to a given total</td><td>what is being sold</td></tr>
 *   <tr><td>at most one {@code CREDIT} line</td><td>how its total is arrived at</td></tr>
 *   <tr><td>{@code customerId} required on {@code CREDIT}</td><td>a surcharge, a commission</td></tr>
 *   <tr><td>cash tendered &ge; its cash line, and the change</td><td></td></tr>
 * </table>
 *
 * <p>Copying it instead was the failure the design document already names about
 * the UI — "the same three buttons would have been built twice and behaved
 * differently" — and it is just as true of the rule that refuses a basket which
 * does not add up.
 *
 * <p>⚠ Nothing here may import Spring or JPA, for the same reason
 * {@code recharge.domain} may not (design-api.md §5.2, §5.4 rule 4): the settle
 * matrix — sums, short tender, credit without a customer — must stay runnable
 * with no database and no context.
 *
 * <p>⚠ THE DEPENDENCY THAT POINTED THE WRONG WAY NOW POINTS AT A SHARED
 * PACKAGE. The 422s thrown here come from one vocabulary, because S4 was
 * explicit that it must "reuse {@code ErrorCode} and the
 * {@code RechargeException} hierarchy rather than starting a second one" —
 * §8.4's "these names are a contract" holds across the whole surface, and a
 * counter deciding where to put the cursor cannot do it from two vocabularies.
 * What was wrong was its address: S4 left it in {@code recharge.exception} and
 * said so in the diff, since a 41-file rename inside a feature commit makes the
 * feature unreviewable. S5 performed it as its own commit — the tree is now
 * {@code com.mangle.retailshopapp.apierror} (next-session-s5.md item 2), which
 * is where a shared wire-error vocabulary belongs.
 */
package com.mangle.retailshopapp.settlement;
