package com.mangle.retailshopapp.water.controller;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import com.mangle.retailshopapp.water.model.PumpActionRequest;

import org.springframework.beans.factory.annotation.Value;

@RestController
@RequestMapping("motor")
public class MotorController {
    private final RestTemplate restTemplate;

    @Value("${water.esp.api.url}")
    private String espWebApi;

    @Value("${water.esp.api.key}")
    private String apiKey;

    public MotorController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @GetMapping("/status")
    public ResponseEntity<Object> getMotorStatus() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", apiKey);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        return restTemplate.exchange(
                espWebApi + "/status",
                HttpMethod.GET,
                entity,
                Object.class);
    }

    @PostMapping("/pump")
    public ResponseEntity<Object> controlPump(@RequestBody PumpActionRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", apiKey);

        String url = String.format("%s/pump?action=%s", espWebApi, request.getAction());
        HttpEntity<String> entity = new HttpEntity<>(headers);

        return restTemplate.exchange(
                url,
                HttpMethod.POST,
                entity,
                Object.class);
    }
}
