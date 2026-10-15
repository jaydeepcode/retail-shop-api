/**
 * The module's pure domain: value objects and enums with no Spring, no JPA and
 * no repositories (design-api.md §5.2).
 *
 * <p>⚠ A DEVIATION FROM §5.1's LAYOUT, which names only {@code controller/},
 * {@code service/}, {@code model/}, {@code repo/} and {@code exception/}. The
 * reason is that §5.2 and §5.4 rule 4 make a hard requirement of these types
 * being constructible without a Spring context — "the settle-basket test matrix
 * (sums, short tender, credit without customer) needs no database at all" — and
 * §5.2 calls this "where the anemia is actually avoided". Putting them in
 * {@code model/} alongside the JPA entities would leave that boundary as a
 * convention; a package makes it visible, and makes "does anything here import
 * jakarta.persistence or org.springframework" a one-line check.
 *
 * <p>Nothing in this package may import Spring or JPA. The arithmetic that
 * decides what a customer owes and what change they are due lives here, and it
 * is the part worth testing exhaustively and cheaply.
 */
package com.mangle.retailshopapp.recharge.domain;
