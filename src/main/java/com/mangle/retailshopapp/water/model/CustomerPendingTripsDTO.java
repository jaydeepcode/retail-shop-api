package com.mangle.retailshopapp.water.model;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CustomerPendingTripsDTO {
    private Integer custId;
    private String customerName;
    private String contactNum;
    private int pendingTrips;
    private BigDecimal balanceAmount;
}
