package com.mangle.retailshopapp.water.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class MotorStatusResponse {
    @JsonProperty("pump_inside")
    private PumpStatus pumpInside;

    @JsonProperty("pump_outside")
    private PumpStatus pumpOutside;

    @JsonProperty("water_level")
    private String waterLevel;

    private long timestamp;
}
