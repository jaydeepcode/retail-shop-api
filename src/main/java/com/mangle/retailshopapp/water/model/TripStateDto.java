package com.mangle.retailshopapp.water.model;

import java.time.LocalDateTime;

import lombok.Data;

@Data
public class TripStateDto {
    private Integer tripId;
    private Integer customerId;
    private TripStatus tripStatus;  
    private PumpUsed pumpUsed;
    private LocalDateTime tripStartTime;
}
