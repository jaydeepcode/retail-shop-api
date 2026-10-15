package com.mangle.retailshopapp.recharge.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Void a completed sale (design-api.md §3.2 endpoint #19).
 *
 * <p>⚠ THE REASON IS MANDATORY, and it is the control rather than paperwork
 * (log §7.1). A void must not become a back door for refunding a DELIVERED
 * recharge, because reversing the float legs would restore float that never came
 * back. There is no second approver — FRD §3 fixes v1 as single-user — so the
 * reason plus float reconciliation is all there is.
 */
public record VoidRechargeRequest(

        @NotBlank(message = "a reason is required to void a recharge")
        @Size(max = 255, message = "a reason is at most 255 characters")
        String reason) {
}
