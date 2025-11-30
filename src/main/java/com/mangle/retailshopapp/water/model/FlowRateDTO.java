package com.mangle.retailshopapp.water.model;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FlowRateDTO {
    private BigDecimal secPerLiter;
    private String pumpType;
    private Integer sampleSize;
    private String calculationSource; // CUSTOMER_HISTORY, GLOBAL_AVG, CONFIG_DEFAULT
    private String lastCalculated;
}

