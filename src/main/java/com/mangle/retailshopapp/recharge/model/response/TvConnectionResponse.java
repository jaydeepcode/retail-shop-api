package com.mangle.retailshopapp.recharge.model.response;

import com.mangle.retailshopapp.recharge.model.RcDishtvDtls;

/**
 * A known TV connection (§3.2 endpoint #3). 458 of them.
 *
 * <p>No {@code customerId}: §4.6 assigns that column and its backfill to V18,
 * which is cutover work, and the match covers only 89 of 458 rows anyway. §4.6 is
 * clear that this is the right model regardless — "staff still pick a CONNECTION;
 * the customer link is advisory."
 */
public record TvConnectionResponse(
        String connectionNumber,
        String operatorCode,
        String name,
        String contactNumber) {

    public static TvConnectionResponse from(RcDishtvDtls d) {
        return new TvConnectionResponse(d.getDishtvNo(), d.getDishtvTyp(), d.getName(),
                d.getContactNum());
    }
}
