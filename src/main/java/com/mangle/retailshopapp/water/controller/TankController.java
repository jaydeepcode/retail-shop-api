package com.mangle.retailshopapp.water.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mangle.retailshopapp.water.model.StatusResponse;

@RestController
@RequestMapping("tank")
public class TankController {

    @Value("${water.esp.api.url}")
    private String espWebApi;

    @Value("${water.esp.api.key}")
    private String apiKey;


    @GetMapping("/isEmpty")
    public ResponseEntity<String> getMotorStatus() {
         return ResponseEntity.ok(StatusResponse.WATER_LEVEL_LOW);
    }
}
