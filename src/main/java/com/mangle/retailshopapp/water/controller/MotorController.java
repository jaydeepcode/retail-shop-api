package com.mangle.retailshopapp.water.controller;

import java.util.Collections;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.mangle.retailshopapp.audit.annotation.Auditable;
import com.mangle.retailshopapp.audit.service.AuditLogService;
import com.mangle.retailshopapp.customer.model.CustomerTripLedger;
import com.mangle.retailshopapp.customer.repo.CustomerTripLedgerRepository;
import com.mangle.retailshopapp.user.comp.JwtUtil;
import com.mangle.retailshopapp.user.model.User;
import com.mangle.retailshopapp.user.repo.UserRepository;
import com.mangle.retailshopapp.water.model.MotorStatusResponse;
import com.mangle.retailshopapp.water.model.PumpStatus;
import com.mangle.retailshopapp.water.model.WaterPurchaseParty;
import com.mangle.retailshopapp.water.repo.WaterPurchasePartyRepo;

@RestController
@RequestMapping("/motor")
public class MotorController {
    private static final Logger logger = LoggerFactory.getLogger(MotorController.class);
    private final RestTemplate restTemplate;
    private final CustomerTripLedgerRepository customerTripLedgerRepository;

    @Value("${water.esp.api.url}")
    private String espWebApi;

    @Value("${water.esp.api.key}")
    private String apiKey;
    
    @Value("${water.esp.mock.enabled:false}")
    private boolean mockEnabled;
    
    // Mock state tracking
    private String pumpInsideStatus = "OFF";
    private String pumpOutsideStatus = "OFF";
    
    @Autowired
    private AuditLogService auditLogService;
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private WaterPurchasePartyRepo waterPartyRepository;

    public MotorController(RestTemplate restTemplate, CustomerTripLedgerRepository customerTripLedgerRepository) {
        this.restTemplate = restTemplate;
        this.customerTripLedgerRepository = customerTripLedgerRepository;
    }

    @GetMapping("/status")
    public ResponseEntity<MotorStatusResponse> getMotorStatus() {
        if (mockEnabled) {
            // Return mock response with current state
            MotorStatusResponse mockResponse = new MotorStatusResponse();
            
            PumpStatus insideStatus = new PumpStatus();
            insideStatus.setStatus(pumpInsideStatus);
            mockResponse.setPumpInside(insideStatus);
            
            PumpStatus outsideStatus = new PumpStatus();
            outsideStatus.setStatus(pumpOutsideStatus);
            mockResponse.setPumpOutside(outsideStatus);
            
            mockResponse.setWaterLevel("NORMAL");
            mockResponse.setTimestamp(System.currentTimeMillis());
            
            return ResponseEntity.ok(mockResponse);
        }
        
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

    @Scheduled(fixedRate = 120000) // Run every 60 seconds (2 minute)
    public void pollMotorStatus() {
        logger.info("Polling motor status to keep ESP server active .....");
        try {
            getMotorStatus();
        } catch (Exception e) {
            logger.error("Error polling motor status: {}", e.getMessage());
        }
    }

    @PostMapping("/pump/{pump}/{action}")
    @PreAuthorize("hasAnyRole('ADMIN', 'CUSTOMER')")
    @Auditable(action = "PUMP_CONTROL")
    public ResponseEntity<Object> controlPump(
            @PathVariable String pump,
            @PathVariable String action,
            @RequestParam(required = false) Integer waterPartyId) {

        // Validate pump parameter
        if (!pump.equals("inside") && !pump.equals("outside")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid pump. Must be 'inside' or 'outside'");
        }

        // Validate action parameter
        if (!action.equals("start") && !action.equals("stop")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid action. Must be 'start' or 'stop'");
        }
        
        // Get authentication context
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        User user = userRepository.findByUsername(auth.getName());
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        
        // Determine target waterPartyId and chargeable status
        Integer targetPartyId = waterPartyId;
        boolean isChargeable = true;
        
        if (!isAdmin) {
            // Customer: use their own waterPartyId from token (if available)
            targetPartyId = waterPartyId; // Could extract from JWT token if needed
            isChargeable = true;
        } else {
            // Admin: non-chargeable if acting on behalf of customer
            isChargeable = (waterPartyId == null);
        }
        
        // Validate waterPartyId is APPROVED (if provided)
        if (targetPartyId != null) {
            WaterPurchaseParty party = waterPartyRepository.findById((long) targetPartyId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid customer"));
            if (!"APPROVED".equals(party.getRegistrationStatus())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Customer not approved");
            }
        }
        
        if (mockEnabled) {
            // Mock mode: update state and return mock response
            if (action.equals("start")) {
                if (pump.equals("inside")) {
                    pumpInsideStatus = "ON";
                } else {
                    pumpOutsideStatus = "ON";
                }
            } else if (action.equals("stop")) {
                if (pump.equals("inside")) {
                    pumpInsideStatus = "OFF";
                } else {
                    pumpOutsideStatus = "OFF";
                }
            }
            
            // Log the pump action
            auditLogService.logPumpAction(
                user.getId(), 
                action, 
                pump, 
                targetPartyId, 
                isChargeable, 
                "127.0.0.1" // TODO: Extract real IP from request
            );
            
            // Return mock success response
            return ResponseEntity.ok(Collections.singletonMap("status", "success"));
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
                // Log the pump action
                auditLogService.logPumpAction(
                    user.getId(), 
                    action, 
                    pump, 
                    targetPartyId, 
                    isChargeable, 
                    "127.0.0.1" // TODO: Extract real IP from request
                );
                
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

    @GetMapping("/trip-time/{tripId}")
    public ResponseEntity<Object> getTripStartTime(@PathVariable Integer tripId) {
        try {
            CustomerTripLedger trip = customerTripLedgerRepository.findById(tripId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Trip not found"));
            
            return ResponseEntity.ok(Collections.singletonMap("startTime", trip.getStartTime()));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to fetch trip start time: " + e.getMessage());
        }
    }
}
