package com.mangle.retailshopapp.admin.controller;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mangle.retailshopapp.customer.model.CustomerDetails;
import com.mangle.retailshopapp.customer.repo.CustomerDetailsRepository;
import com.mangle.retailshopapp.user.model.User;
import com.mangle.retailshopapp.user.repo.UserRepository;
import com.mangle.retailshopapp.water.model.WaterPurchaseParty;
import com.mangle.retailshopapp.water.model.WaterPurchasePartyWithNameDTO;
import com.mangle.retailshopapp.water.repo.WaterPurchasePartyRepo;

@RestController
@RequestMapping("/admin/customers")
@PreAuthorize("hasRole('ADMIN')")
public class CustomerApprovalController {

    @Autowired
    private WaterPurchasePartyRepo waterPartyRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CustomerDetailsRepository customerDetailsRepository;

    @GetMapping("/pending")
    public ResponseEntity<?> getPendingCustomers() {
        try {
            List<WaterPurchasePartyWithNameDTO> pending = waterPartyRepository.findPendingCustomersWithNames();
            return ResponseEntity.ok(pending);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(
                    Map.of("success", false, "message", "Failed to fetch pending customers: " + e.getMessage()));
        }
    }

    @PostMapping("/{waterPartyId}/approve")
    public ResponseEntity<?> approveCustomer(@PathVariable Long waterPartyId,
            @RequestParam int adminId) {
        try {
            WaterPurchaseParty party = waterPartyRepository
                    .findPartyDetailsByCustomerId(Integer.valueOf(waterPartyId.intValue()))
                    .orElseThrow(() -> new RuntimeException("Customer not found"));
            CustomerDetails customerDetails = customerDetailsRepository.findById(Long.valueOf(party.getCustomerId()))
                    .orElseThrow(() -> new RuntimeException("Customer details not found"));

            User user = userRepository.findById((long) customerDetails.getUserId())
                    .orElseThrow(() -> new RuntimeException("User not found"));

            // Approve both
            user.setAccountStatus("APPROVED");
            user.setApprovedBy(adminId);
            user.setApprovedDate(LocalDateTime.now());
            userRepository.save(user);

            customerDetails.setStatusCode("ACTIVE");
            customerDetailsRepository.save(customerDetails);

            party.setApprovedBy(adminId);
            party.setApprovedDate(LocalDateTime.now());
            party.setActive(true);
            waterPartyRepository.save(party);

            // TODO: Send push notification
            // pushNotificationService.sendNotification(
            // user.getId(),
            // "Account Approved",
            // "Your water vendor account has been approved. You can now login."
            // );

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Customer approved successfully"));

        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(
                    Map.of("success", false, "message", "Failed to approve customer: " + e.getMessage()));
        }
    }

    @PostMapping("/{waterPartyId}/reject")
    public ResponseEntity<?> rejectCustomer(@PathVariable Long waterPartyId,
            @RequestParam int adminId,
            @RequestParam(required = false) String reason) {
        try {
            WaterPurchaseParty party = waterPartyRepository
                    .findPartyDetailsByCustomerId(Integer.valueOf(waterPartyId.intValue()))
                    .orElseThrow(() -> new RuntimeException("Customer not found"));

            CustomerDetails customerDetails = customerDetailsRepository.findById(Long.valueOf(party.getCustomerId()))
                    .orElseThrow(() -> new RuntimeException("Customer details not found"));
            User user = userRepository.findById((long) customerDetails.getUserId())
                    .orElseThrow(() -> new RuntimeException("User not found"));

            // Reject both
            user.setAccountStatus("DISABLED");
            user.setApprovedBy(adminId);
            user.setApprovedDate(LocalDateTime.now());
            userRepository.save(user);

            customerDetails.setStatusCode("INACTIVE");
            customerDetailsRepository.save(customerDetails);

            party.setApprovedBy(adminId);
            party.setApprovedDate(LocalDateTime.now());
            party.setActive(false);
            waterPartyRepository.save(party);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Customer rejected successfully"));

        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(
                    Map.of("success", false, "message", "Failed to reject customer: " + e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<?> getAllCustomers() {
        try {
            // Only customers with WaterPurchaseParty entries
            List<WaterPurchasePartyWithNameDTO> customers = waterPartyRepository.findApprovedCustomersWithNames();
            return ResponseEntity.ok(customers);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(
                    Map.of("success", false, "message", "Failed to fetch customers: " + e.getMessage()));
        }
    }
}
