package com.mangle.retailshopapp.customer.model;

import lombok.Data;

@Data
public class CustomerRegistrationRequest {
    private String username;
    private String password;
    private String firstName;
    private String lastName;
    private String storageType;
    private int tankerCapacity;
    private String vehicleNumber;
    private String address;
    private String contactNumber;
    private String location;
}
