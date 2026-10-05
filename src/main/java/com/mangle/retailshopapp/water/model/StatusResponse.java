package com.mangle.retailshopapp.water.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

@Data
public class StatusResponse {
    public static final String WATER_LEVEL_HIGH = "HIGH";
    public static final String WATER_LEVEL_LOW = "LOW";
    
    private String status;

    @JsonProperty("water_level")
    private String waterLevel;

    private long timestamp;
}
