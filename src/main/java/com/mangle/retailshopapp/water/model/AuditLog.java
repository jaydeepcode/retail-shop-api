package com.mangle.retailshopapp.water.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "wt_adt_lgs")
@Data
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "user_id", nullable = false)
    private int userId;
    
    @Column(name = "action_type", nullable = false, length = 50)
    private String actionType; // PUMP_START, PUMP_STOP, LOGIN, APPROVAL, etc.
    
    @Column(name = "action_description", length = 500)
    private String actionDescription;
    
    @Column(name = "target_entity", length = 50)
    private String targetEntity; // PUMP, USER, CUSTOMER, etc.
    
    @Column(name = "target_id")
    private Integer targetId;
    
    @Column(name = "performed_by", nullable = false)
    private int performedBy; // User ID who performed action
    
    @Column(name = "performed_for_water_party_id")
    private Integer performedForWaterPartyId; // For admin actions on behalf of customer
    
    @Column(name = "ip_address", length = 45)
    private String ipAddress;
    
    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp = LocalDateTime.now();
    
    @Column(name = "is_chargeable_action", nullable = false)
    private boolean isChargeableAction = true; // False for admin test operations
}
