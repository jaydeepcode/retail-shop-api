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

import com.mangle.retailshopapp.customer.model.CustomerDetails;
import com.mangle.retailshopapp.customer.model.CustomerRegistrationRequest;
import com.mangle.retailshopapp.customer.repo.CustomerDetailsRepository;
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
    private CustomerDetailsRepository customerDetailsRepository;
    
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
            
            // Create User (authentication only)
            User user = new User();
            user.setUsername(request.getUsername());
            user.setPassword(passwordEncoder.encode(request.getPassword()));
            user.setRoles(List.of("ROLE_CUSTOMER"));
            user.setAccountStatus("PENDING");
            user.setCreatedDate(LocalDateTime.now());
            user = userRepository.save(user);
            
            // Create Customer (business entity)
            CustomerDetails customer = new CustomerDetails();
            customer.setFirstName(request.getFirstName());
            customer.setLastName(request.getLastName());
            customer.setContactNum(request.getContactNumber());
            customer.setUserId(user.getId());
            customer.setActive(false); // Activated on approval
            customer.setCreDttm(LocalDateTime.now());
            customer = customerDetailsRepository.save(customer);
            
            // Create WaterPurchaseParty (business-specific)
            WaterPurchaseParty party = new WaterPurchaseParty();
            party.setCustomerId(customer.getCustId()); // Now properly references customer
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
