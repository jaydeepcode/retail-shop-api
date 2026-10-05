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
@Table(name = "trip_flow_rate_anomalies")
public class TripFlowRateAnomaly {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    
    @Column(name = "trip_id", nullable = false)
    private Integer tripId;
    
    @Column(name = "customer_id", nullable = false)
    private Integer customerId;
    
    @Column(name = "measured_sec_per_liter", nullable = false, precision = 5, scale = 3)
    private BigDecimal measuredSecPerLiter;
    
    @Column(name = "expected_sec_per_liter", nullable = false, precision = 5, scale = 3)
    private BigDecimal expectedSecPerLiter;
    
    @Column(name = "deviation_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal deviationPercent;
    
    @Column(name = "reason", nullable = false, length = 50)
    private String reason;
    
    @Column(name = "flagged_at", nullable = false)
    private LocalDateTime flaggedAt;
    
    @Column(name = "reviewed_by")
    private Integer reviewedBy;
    
    @Column(name = "review_notes", length = 255)
    private String reviewNotes;
    
    @Column(name = "resolution", length = 50)
    private String resolution;
}

