package com.mangle.retailshopapp.water.model;

import java.math.BigDecimal;
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
@Table(name = "pump_flow_rate_config")
public class FlowRateConfig {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    
    @Column(name = "pump_type", nullable = false, length = 20)
    private String pumpType;
    
    @Column(name = "default_sec_per_liter", nullable = false, precision = 5, scale = 3)
    private BigDecimal defaultSecPerLiter;
    
    @Column(name = "customer_id")
    private Integer customerId;
    
    @Column(name = "effective_from", nullable = false)
    private LocalDateTime effectiveFrom;
    
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
    
    @Column(name = "notes", length = 255)
    private String notes;
    
    @Column(name = "created_by")
    private Integer createdBy;
    
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}

