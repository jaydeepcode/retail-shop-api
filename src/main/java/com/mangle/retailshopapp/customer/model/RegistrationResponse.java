package com.mangle.retailshopapp.customer.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RegistrationResponse {
    private boolean success;
    private String message;
}




