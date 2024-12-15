package com.mangle.retailshopapp.water.controller;

import java.math.BigDecimal;
import java.util.Collections;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mangle.retailshopapp.customer.model.CustomerRegisterDto;
import com.mangle.retailshopapp.customer.service.CustomerRegistrationService;
import com.mangle.retailshopapp.water.model.CustomerPayment;
import com.mangle.retailshopapp.water.model.WaterPurchaseTransactionDTO;
import com.mangle.retailshopapp.water.service.WaterTransactionService;

@RestController
@RequestMapping("party")
public class PurchasePartyController {

    @Autowired
    private WaterTransactionService service;

    @Autowired
    private CustomerRegistrationService customerRegistrationService;

    @GetMapping("/transactions")
    public WaterPurchaseTransactionDTO searchCustomers(@RequestParam Integer customerId) {
        return service.getCustomerTransactions(customerId);
    }

    @PostMapping("/record-trip")
    public WaterPurchaseTransactionDTO performTransaction(@RequestParam Integer customerId,
            @RequestParam Integer tripAmount) {
        return service.generateAndSaveTrip(customerId, tripAmount);
    }

    @PostMapping("/deposit-amount")
    public WaterPurchaseTransactionDTO depositTransaction(@RequestParam Integer customerId,
            @RequestBody CustomerPayment custPayment) {
        return service.persistPayment(customerId, custPayment);
    }

    @PostMapping("/register")
    public ResponseEntity<?> registerCustomer(@RequestBody CustomerRegisterDto customerRegisterDto) {
        try {
            customerRegistrationService.registerCustomer(customerRegisterDto);
            return ResponseEntity.ok(Collections.singletonMap("message", "Registration Successful"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Collections.singletonMap("message", "Registration Failed: " + e.getMessage()));
        }
    }

    @GetMapping("/check-contact/{contactNum}")
    public ResponseEntity<Boolean> checkContact(@PathVariable String contactNum) {
        boolean isRegistered = customerRegistrationService.isContactRegistered(contactNum);
        return ResponseEntity.ok(isRegistered);
    }

    @GetMapping("/trip-amount/{custId}")
    public ResponseEntity<BigDecimal> caclculateTripAmount(@PathVariable String custId) {
        BigDecimal tripAmount = service.getCalculatedAmount(custId);
        return ResponseEntity.ok(tripAmount);
    }
}