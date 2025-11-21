package com.mangle.retailshopapp.water.model;

import lombok.Data;

@Data
public class WaterPurchasePartyWithNameDTO {
    
    // Essential fields from WaterPurchaseParty
    private int id;
    private int customerId;
    private String storageTypeCode;
    private String storageType;
    private int capacity;
    private String address;
    private Integer userId; // From CustomerDetails.userId
    private String status;
    private String contactNumber;
    
    // Additional field for customer name
    private String customerName;
    
    // Constructor for JPQL query projection
    public WaterPurchasePartyWithNameDTO(int id, int customerId, String storageTypeCode, String storageType, int capacity, 
                                       String address, Integer userId, String status, String contactNumber, String customerName) {
        this.id = id;
        this.customerId = customerId;
        this.storageTypeCode = storageTypeCode;
        this.storageType = storageType;
        this.capacity = capacity;
        this.address = address;
        this.userId = userId;
        this.status = status;
        this.contactNumber = contactNumber;
        this.customerName = customerName;
    }
    
    // Constructor to create DTO from WaterPurchaseParty and customer name
    public WaterPurchasePartyWithNameDTO(WaterPurchaseParty party, String customerName, Integer userId) {
        this.id = party.getId();
        this.customerId = party.getCustomerId();
        this.storageTypeCode = party.getStorageTypeCode();
        this.storageType = party.getStorageType();
        this.capacity = party.getCapacity();
        this.address = party.getAddress();
        this.userId = userId; // Passed from CustomerDetails
        this.contactNumber = party.getContactNumber();
        this.customerName = customerName;
        this.status = party.isActive() ? "ACTIVE" : "INACTIVE";
    }
}
