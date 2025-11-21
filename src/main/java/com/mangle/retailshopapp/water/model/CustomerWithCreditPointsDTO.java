package com.mangle.retailshopapp.water.model;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CustomerWithCreditPointsDTO {
    private Integer custId;
    private String customerName;
    private String contactNum;
    private Integer creditPoints;  // Can be positive, negative, or zero
    private BigDecimal balanceAmount;
    private Boolean hasActiveTrip;
}



