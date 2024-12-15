package com.mangle.retailshopapp.customer.model;

import lombok.Data;


@Data
public class CustomerRegisterDto {

    private String customerName;
    private String contactNum;
    private String storageType;

    private int capacity;
    private String vehicleNumber;
    private String address;
    private String notes;
}
