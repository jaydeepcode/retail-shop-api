package com.mangle.retailshopapp.recharge.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Create a walk-in customer inline: name and mobile only (§3.2 endpoint #5).
 *
 * <p>⚠ {@code lastName} IS NOT REQUIRED, and that is measured rather than lax.
 * §6.2: 147 of 506 existing rows have a null, blank or "." last name, and a
 * walk-in may give one name. Making it mandatory would mean staff typing "." to
 * get past the form, which is how those 147 rows happened.
 *
 * <p>The mobile pattern reuses {@code CustomerRegistrationRequest:45} verbatim,
 * so the two ways into {@code rs_cust_dtls} agree about what a mobile number is.
 */
public record CreateCustomerRequest(

        @NotBlank(message = "a first name is required")
        @Size(max = 45, message = "a first name is at most 45 characters")
        String firstName,

        @Size(max = 45, message = "a last name is at most 45 characters")
        String lastName,

        @NotBlank(message = "a mobile number is required")
        @Pattern(regexp = "^[0-9]{10}$", message = "a mobile number is 10 digits")
        String mobile) {
}
