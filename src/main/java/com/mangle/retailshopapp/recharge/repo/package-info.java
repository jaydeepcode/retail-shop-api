/**
 * The module's repositories, deliberately split by whether a financial year may
 * apply (design-domain.md §7.1(d)).
 *
 * <p>⚠ THE SPLIT IS THE ENFORCEMENT, not a convention. Requirement 9 says the
 * financial year is a view filter only and must never scope open items, and §7.1
 * makes that structural:
 *
 * <ul>
 *   <li>{@link com.mangle.retailshopapp.recharge.repo.RechargeOpenItemsRepository}
 *       takes NO date parameter on any method — the worklist, unresolved failures
 *       and credit balances live here;
 *   <li>{@link com.mangle.retailshopapp.recharge.repo.RechargeHistoryRepository}
 *       takes {@code fyStart}/{@code fyEnd} as REQUIRED parameters — history and
 *       the day summary live here.
 * </ul>
 *
 * <p>It is then impossible to leak a year filter into an open-items list, or to
 * forget one on history, without editing an interface. §7.1 calls this "cheaper
 * and more durable than a code-review rule", and the concrete failure it prevents
 * is specific: the first time a year reaches the credit-balance query, 33 of 34
 * open balances — whose last activity is 2019-2025 — vanish from the screen and
 * staff stop chasing debt.
 */
package com.mangle.retailshopapp.recharge.repo;
