package com.mangle.retailshopapp.water.model;

import lombok.Data;

@Data
public class PumpActionResponse {
    private boolean success;
    private String status;
    private String message;
}
