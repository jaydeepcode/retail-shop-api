package com.mangle.retailshopapp.user.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "rc_usr_api_kys")
@Data
public class UserApiKey {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    
    @Column(name = "user_id", nullable = false)
    private int userId;
    
    @Column(name = "api_key", nullable = false, unique = true, length = 255)
    private String apiKey;
    
    @Column(name = "water_party_id")
    private Integer waterPartyId; // Quick access for customer operations
    
    @Column(name = "created_date", nullable = false)
    private LocalDateTime createdDate;
    
    @Column(name = "expiry_date", nullable = false)
    private LocalDateTime expiryDate;
    
    @Column(name = "last_used_date")
    private LocalDateTime lastUsedDate;
    
    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;
    
    @Column(name = "device_info", length = 255)
    private String deviceInfo; // Platform, version, etc.
}
