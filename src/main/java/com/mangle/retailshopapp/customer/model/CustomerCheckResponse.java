package com.mangle.retailshopapp.customer.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CustomerCheckResponse {
    private boolean success;
    private String message;
    private Integer custId;
    private String firstName;
    private String lastName;
    private String contactNum;
    private String email;
    private boolean hasUserAccount;

    // WaterPurchaseParty details
    private String storageType;
    private Integer capacity;
    private String vehicleNumber;
    private String address;
    private String location;
}

