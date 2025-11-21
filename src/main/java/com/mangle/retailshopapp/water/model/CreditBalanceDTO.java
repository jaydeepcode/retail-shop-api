package com.mangle.retailshopapp.water.model;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreditBalanceDTO {
    private BigDecimal balanceAmount;
    private Integer creditPoints;
    private Boolean hasActiveTrip;
}


