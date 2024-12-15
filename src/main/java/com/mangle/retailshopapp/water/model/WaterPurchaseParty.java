package com.mangle.retailshopapp.water.model;

import java.time.LocalDate;
import java.util.Date;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
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
}
