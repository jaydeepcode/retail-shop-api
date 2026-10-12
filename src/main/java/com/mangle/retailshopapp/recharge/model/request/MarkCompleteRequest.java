package com.mangle.retailshopapp.recharge.model.request;

import jakarta.validation.constraints.Size;

/**
 * Mark a recharge done (design-api.md §3.2 endpoint #9).
 *
 * <p>{@code routeCode} is the override log §14.6 moved here from the settle
 * request: at the moment the customer pays nobody knows which connection will be
 * used, and the route is not needed until the float is touched at completion.
 * Both fields are optional — the common case is an empty body.
 */
public record MarkCompleteRequest(

        @Size(max = 64, message = "a vendor reference is at most 64 characters")
        String vendorRef,

        @Size(max = 20, message = "a route code is at most 20 characters")
        String routeCode) {
}
