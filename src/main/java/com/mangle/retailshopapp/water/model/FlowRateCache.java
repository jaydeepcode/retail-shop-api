package com.mangle.retailshopapp.water.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Entity
@Table(name = "pump_flow_rate_cache")
@IdClass(FlowRateCacheId.class)
public class FlowRateCache {
    
    @Id
    @Column(name = "customer_id", nullable = false)
    private Integer customerId;
    
    @Id
    @Column(name = "pump_type", nullable = false, length = 20)
    private String pumpType;
    
    @Column(name = "calculated_sec_per_liter", nullable = false, precision = 5, scale = 3)
    private BigDecimal calculatedSecPerLiter;
    
    @Column(name = "sample_count", nullable = false)
    private Integer sampleCount;
    
    @Column(name = "last_calculated", nullable = false)
    private LocalDateTime lastCalculated;
    
    @Column(name = "calculation_source", nullable = false, length = 50)
    private String calculationSource;
}

