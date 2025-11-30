package com.mangle.retailshopapp.customer.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.mangle.retailshopapp.water.model.PumpUsed;
import com.mangle.retailshopapp.water.model.TripStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Entity
@Table(name = "wt_purchase_details") 
public class CustomerTripLedger {

   @Id 
   @GeneratedValue(strategy = GenerationType.IDENTITY) 
   @Column(name = "ID") 
   private int id; 
   
   @Column(name = "CUST_ID", nullable = false) 
   private int custId; 

   @Column(name = "TRIP_DATE_TIME", nullable = false) 
   private LocalDateTime tripDateTime;
   
   @Column(name = "CREDIT_AMOUNT", precision = 10, scale = 2) 
   private BigDecimal creditAmount; 
   
   @Column(name = "DEPOSIT_AMOUNT", precision = 10, scale = 2) 
   private BigDecimal depositAmount; 
   
   @Column(name = "BALANCE_AMOUNT", precision = 10, scale = 2) 
   private BigDecimal balanceAmount;
    
   @Column(name = "CRE_BY", nullable = false) 
   private String credBy;

   @Column(name = "PUMP_USED_CODE", length = 30)
   @Enumerated(EnumType.STRING)
   private PumpUsed pumpUsed;

   @Column(name = "STATUS_CODE", length = 30)
   @Enumerated(EnumType.STRING)
   private TripStatus status;

   @Column(name = "start_time")
   private LocalDateTime startTime;

   @Column(name = "end_time")
   private LocalDateTime endTime;

   @Column(name = "expected_duration_sec")
   private Integer expectedDurationSec;

   @Column(name = "auto_stop_scheduled")
   private Boolean autoStopScheduled = false;

   @Column(name = "auto_stopped")
   private Boolean autoStopped = false;

   @Column(name = "auto_stop_attempted_at")
   private LocalDateTime autoStopAttemptedAt;

}
