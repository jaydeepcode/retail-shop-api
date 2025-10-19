package com.mangle.retailshopapp.customer.controller;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mangle.retailshopapp.customer.model.CustomerRegistrationRequest;
import com.mangle.retailshopapp.user.model.User;
import com.mangle.retailshopapp.user.repo.UserRepository;
import com.mangle.retailshopapp.water.model.WaterPurchaseParty;
import com.mangle.retailshopapp.water.repo.WaterPurchasePartyRepo;

@RestController
@RequestMapping("/customer")
public class CustomerRegistrationController {
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private WaterPurchasePartyRepo waterPartyRepository;
    
    @Autowired
    private PasswordEncoder passwordEncoder;
    
    @PostMapping("/register")
    public ResponseEntity<?> registerCustomer(@RequestBody CustomerRegistrationRequest request) {
        try {
            // Check if username already exists
            if (userRepository.findByUsername(request.getUsername()) != null) {
                return ResponseEntity.badRequest().body(
                    Map.of("success", false, "message", "Username already exists")
                );
            }
            
            // Create User
            User user = new User();
            user.setUsername(request.getUsername());
            user.setPassword(passwordEncoder.encode(request.getPassword()));
            user.setFirstName(request.getFirstName());
            user.setLastName(request.getLastName());
            user.setRoles(List.of("ROLE_CUSTOMER"));
            user.setAccountStatus("PENDING");
            user.setCreatedDate(LocalDateTime.now());
            user = userRepository.save(user);
            
            // Create WaterPurchaseParty
            WaterPurchaseParty party = new WaterPurchaseParty();
            party.setUserId(user.getId());
            party.setCustomerId(user.getId()); // Or generate separate customer ID
            party.setStorageType(request.getStorageType());
            party.setCapacity(request.getTankerCapacity());
            party.setVehicleNumber(request.getVehicleNumber());
            party.setAddress(request.getAddress());
            party.setContactNumber(request.getContactNumber());
            party.setLocation(request.getLocation());
            party.setRegistrationStatus("PENDING");
            party.setRegistrationDate(LocalDate.now());
            party.setActive(false); // Activated on approval
            waterPartyRepository.save(party);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Registration submitted successfully. Please wait for admin approval."
            ));
            
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(
                Map.of("success", false, "message", "Registration failed: " + e.getMessage())
            );
        }
    }
}
