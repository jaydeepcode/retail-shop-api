package com.mangle.retailshopapp.recharge.model.request;

import jakarta.validation.constraints.Size;

/** Mark a recharge failed. The reason is optional (design-api.md §3.2 #10). */
public record MarkFailedRequest(

        @Size(max = 255, message = "a reason is at most 255 characters")
        String reason) {
}
