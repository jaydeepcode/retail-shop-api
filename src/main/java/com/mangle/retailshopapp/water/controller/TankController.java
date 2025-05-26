package com.mangle.retailshopapp.water.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

@RestController
@RequestMapping("tank")
public class TankController {

    private RestTemplate restTemplate;
    @Value("${water.esp.api.url}")
    private String espWebApi;

    @Value("${water.esp.api.key}")
    private String apiKey;

    public TankController(RestTemplate restTemplate) {
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
}
