package com.mangle.retailshopapp.water.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Entity
@Table(name = "wt_purchase_party")
public class WaterPurchaseParty {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(name = "customer_id", nullable = false)
    private int customerId;

    @Column(name = "storage_type", nullable = false)
    private String storageType;

    @Column(name = "capacity", nullable = false)
    private int capacity;

    @Column(name = "vehicle_number")
    private String vehicleNumber;

    @Column(name = "registration_date", nullable = false)
    private LocalDate registrationDate;

    @Column(name = "address")
    private String address;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    // New columns for user authentication and approval workflow
    @Column(name = "user_id")
    private Integer userId; // Link to User table for authentication

    @Column(name = "registration_status", nullable = false, length = 20)
    private String registrationStatus = "PENDING"; // PENDING, APPROVED, REJECTED

    @Column(name = "approved_by")
    private Integer approvedBy; // Admin ID who approved

    @Column(name = "approved_date")
    private LocalDateTime approvedDate;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "contact_number", length = 20)
    private String contactNumber; // If not already present

    @Column(name = "location", length = 255)
    private String location; // Can reuse 'address' if preferred
}
