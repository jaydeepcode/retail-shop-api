package com.mangle.retailshopapp.water.controller;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.mangle.retailshopapp.water.model.MotorStatusResponse;
import org.springframework.beans.factory.annotation.Value;

@RestController
@RequestMapping("/api/motor")
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
    public ResponseEntity<MotorStatusResponse> getMotorStatus() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", apiKey);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<MotorStatusResponse> response = restTemplate.exchange(
                    espWebApi + "/status",
                    HttpMethod.GET,
                    entity,
                    MotorStatusResponse.class);
            return response;
        } catch (HttpClientErrorException e) {
            throw new ResponseStatusException(e.getStatusCode(), e.getMessage(), e);
        }
    }

    @PostMapping("/pump/{pump}/{action}")
    public ResponseEntity<Object> controlPump(
            @PathVariable String pump,
            @PathVariable String action) {

        // Validate pump parameter
        if (!pump.equals("inside") && !pump.equals("outside")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid pump. Must be 'inside' or 'outside'");
        }

        // Validate action parameter
        if (!action.equals("start") && !action.equals("stop")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid action. Must be 'start' or 'stop'");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", apiKey);

        String url = String.format("%s/pump?pump=%s&action=%s", espWebApi, pump, action);
        HttpEntity<String> entity = new HttpEntity<>(headers);
        
        try {
            ResponseEntity<Object> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    Object.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                return response;
            }

            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to control pump");
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Cannot start pump while another pump is running", e);
            }
            throw new ResponseStatusException(e.getStatusCode(), e.getMessage(), e);
        }
    }
}
