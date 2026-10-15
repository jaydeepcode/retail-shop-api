package com.mangle.retailshopapp.recharge.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A known TV connection (design-domain.md §4.6). 458 rows.
 *
 * <p>Searched by customer name, contact number or connection number (§3.2
 * endpoint #3). §7.1(a) is worth heeding on the search shape: name search works
 * HERE and on credit sales, but not as a primary filter over recharges — 9,776
 * distinct recharged mobile numbers against 244 matching a customer means most
 * recharges have no name at all.
 *
 * <p>⚠ {@code CUST_ID} AND THE {@code DISHTV_TYP} FK ARE NOT HERE YET. §4.6
 * assigns both to V18, which is cutover work: the FK needs two data repairs first
 * (two {@code ZINGTV} rows repointed, and one row whose {@code DISHTV_TYP} is
 * NULL), and the {@code CUST_ID} backfill matches only 89 of 458 rows. So the
 * customer link is unavailable to S3, and the screens treat the connection as the
 * thing staff pick — which §4.6 says is the right model anyway: "staff still pick
 * a CONNECTION; the customer link is advisory".
 */
@Getter
@Setter
@Entity
@Table(name = "rc_dishtv_dtls")
public class RcDishtvDtls {

    @Id
    @Column(name = "DISHTV_NO", nullable = false, length = 12)
    private String dishtvNo;

    /** The TV operator code. Nullable until V18 repairs the one NULL row. */
    @Column(name = "DISHTV_TYP", length = 10)
    private String dishtvTyp;

    @Column(name = "CONTACT_NUM", length = 45)
    private String contactNum;

    @Column(name = "NAME", length = 45)
    private String name;
}
