package com.mangle.retailshopapp.customer.model;

import lombok.Data;


@Data
public class CustomerRegisterDto {

    private String firstName;
    private String lastName;
    private String customerName; // Legacy field for backward compatibility
    private String contactNum;
    private String storageType;

    private int capacity;
    private String vehicleNumber;
    private String address;
    private String notes;
}
