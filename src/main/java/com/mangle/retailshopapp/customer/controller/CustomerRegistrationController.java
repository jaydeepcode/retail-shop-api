package com.mangle.retailshopapp.customer.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.mangle.retailshopapp.customer.model.CustomerCheckResponse;
import com.mangle.retailshopapp.customer.model.CustomerRegistrationRequest;
import com.mangle.retailshopapp.customer.model.RegistrationResponse;
import com.mangle.retailshopapp.customer.service.CustomerRegistrationService;

@RestController
@RequestMapping("/customer")
public class CustomerRegistrationController {
    
    @Autowired
    private CustomerRegistrationService registrationService;
    
    @GetMapping("/check-mobile")
    public ResponseEntity<CustomerCheckResponse> checkMobileNumber(@RequestParam String mobile) {
        CustomerCheckResponse response = registrationService.checkMobileNumber(mobile);
        return ResponseEntity.ok(response);
    }
    
    @PostMapping("/register")
    public ResponseEntity<RegistrationResponse> registerCustomer(@Valid @RequestBody CustomerRegistrationRequest request) {
        RegistrationResponse response = registrationService.registerCustomer(request);
        return ResponseEntity.ok(response);
    }
}
