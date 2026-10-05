package com.mangle.retailshopapp.water.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
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

    @Column(name = "STORAGE_TYPE_CODE", length = 30)
    private String storageTypeCode;

    @Transient
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

    public String getStorageType() {
        return storageType != null ? storageType : deriveStorageTypeLabel(this.storageTypeCode);
    }

    public void setStorageType(String storageType) {
        this.storageType = storageType;
        this.storageTypeCode = deriveStorageTypeCode(storageType);
    }

    public void setStorageTypeCode(String storageTypeCode) {
        this.storageTypeCode = storageTypeCode;
        this.storageType = deriveStorageTypeLabel(storageTypeCode);
    }

    @PrePersist
    @PreUpdate
    private void syncStorageTypeCode() {
        if (this.storageType != null) {
            this.storageTypeCode = deriveStorageTypeCode(this.storageType);
        }
    }

    @PostLoad
    private void hydrateStorageType() {
        this.storageType = deriveStorageTypeLabel(this.storageTypeCode);
    }

    private static String deriveStorageTypeCode(String storageType) {
        if (storageType == null || storageType.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = storageType.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        return switch (normalized) {
            case "TANKER" -> "TANKER";
            case "SMALL_PURCHASE" -> "SMALL_PURCHASE";
            default -> "UNKNOWN";
        };
    }

    private static String deriveStorageTypeLabel(String storageTypeCode) {
        if (storageTypeCode == null) {
            return "Unknown";
        }
        return switch (storageTypeCode) {
            case "TANKER" -> "Tanker";
            case "SMALL_PURCHASE" -> "Small Purchase";
            default -> "Unknown";
        };
    }
}
