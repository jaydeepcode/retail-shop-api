package com.mangle.retailshopapp.water.model;

import lombok.Data;

@Data
public class WaterPurchasePartyWithNameDTO {
    
    // Essential fields from WaterPurchaseParty
    private int id;
    private int customerId;
    private String storageType;
    private int capacity;
    private String address;
    private Integer userId; // From CustomerDetails.userId
    private String registrationStatus;
    private String contactNumber;
    
    // Additional field for customer name
    private String customerName;
    
    // Constructor for JPQL query projection
    public WaterPurchasePartyWithNameDTO(int id, int customerId, String storageType, int capacity, 
                                       String address, Integer userId, String registrationStatus, String contactNumber, String customerName) {
        this.id = id;
        this.customerId = customerId;
        this.storageType = storageType;
        this.capacity = capacity;
        this.address = address;
        this.userId = userId;
        this.registrationStatus = registrationStatus;
        this.contactNumber = contactNumber;
        this.customerName = customerName;
    }
    
    // Constructor to create DTO from WaterPurchaseParty and customer name
    public WaterPurchasePartyWithNameDTO(WaterPurchaseParty party, String customerName, Integer userId) {
        this.id = party.getId();
        this.customerId = party.getCustomerId();
        this.storageType = party.getStorageType();
        this.capacity = party.getCapacity();
        this.address = party.getAddress();
        this.userId = userId; // Passed from CustomerDetails
        this.registrationStatus = party.getRegistrationStatus();
        this.contactNumber = party.getContactNumber();
        this.customerName = customerName;
    }
}
