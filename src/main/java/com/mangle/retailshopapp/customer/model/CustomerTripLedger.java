package com.mangle.retailshopapp.customer.model;

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
    
}
