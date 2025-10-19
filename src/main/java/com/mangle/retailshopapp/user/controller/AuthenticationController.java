package com.mangle.retailshopapp.user.controller;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

import com.mangle.retailshopapp.user.comp.JwtUtil;
import com.mangle.retailshopapp.user.model.RegisterUserVo;
import com.mangle.retailshopapp.user.model.User;
import com.mangle.retailshopapp.user.repo.UserRepository;
import com.mangle.retailshopapp.user.service.ApiKeyService;
import com.mangle.retailshopapp.user.service.RetailAppUserService;
import com.mangle.retailshopapp.water.model.WaterPurchaseParty;
import com.mangle.retailshopapp.water.repo.WaterPurchasePartyRepo;

@RestController
public class AuthenticationController {
    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private JwtUtil jwtTokenUtil;

    @Autowired
    private RetailAppUserService userDetailsService;
    
    @Autowired
    private ApiKeyService apiKeyService;
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private WaterPurchasePartyRepo waterPartyRepository;

    @PostMapping("/authenticate")
    public ResponseEntity<?> createAuthenticationToken(@RequestBody AuthenticationRequest authenticationRequest,
            HttpServletRequest request) throws Exception {
        authenticate(authenticationRequest.getUsername(), authenticationRequest.getPassword());
        
        UserDetails userDetails = userDetailsService.loadUserByUsername(authenticationRequest.getUsername());
        User user = userRepository.findByUsername(authenticationRequest.getUsername());
        
        // Check account status
        if (!"APPROVED".equals(user.getAccountStatus()) && !"ACTIVE".equals(user.getAccountStatus())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                Map.of("success", false, "message", "Account pending approval", 
                       "accountStatus", user.getAccountStatus())
            );
        }
        
        // Get WaterPurchaseParty for customer
        Integer waterPartyId = null;
        Integer tankerCapacity = null;
        if (user.getRoles().contains("ROLE_CUSTOMER")) {
            WaterPurchaseParty party = waterPartyRepository.findByUserId(user.getId()).orElse(null);
            if (party != null) {
                waterPartyId = party.getId();
                tankerCapacity = party.getCapacity();
            }
        }
        
        // Extract device info from request or User-Agent header
        String deviceInfo = authenticationRequest.getDeviceInfo();
        if (deviceInfo == null || deviceInfo.isEmpty()) {
            deviceInfo = extractDeviceFromUserAgent(request.getHeader("User-Agent"));
        }
        
        // Generate tokens
        String accessToken = jwtTokenUtil.generateAccessToken(userDetails, waterPartyId);
        String refreshToken = jwtTokenUtil.generateRefreshToken(userDetails);
        String apiKey = apiKeyService.generateApiKey(user, waterPartyId, deviceInfo);
        
        // Update last login
        user.setLastLoginDate(LocalDateTime.now());
        if ("APPROVED".equals(user.getAccountStatus()) || user.getRoles().contains("ROLE_ADMIN")) {
            user.setAccountStatus("ACTIVE");
        }
        userRepository.save(user);
        
        // Determine user type
        String userType = user.getRoles().contains("ROLE_ADMIN") ? "admin" : "customer";
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "Authentication successful",
            "accessToken", accessToken,
            "refreshToken", refreshToken,
            "apiKey", apiKey,
            "userType", userType,
            "userId", user.getId(),
            "waterPartyId", waterPartyId != null ? waterPartyId : "",
            "accountStatus", user.getAccountStatus(),
            "tankerCapacity", tankerCapacity != null ? tankerCapacity : 0
        ));
    }

    @PostMapping("/signup")
    public ResponseEntity<?> createUser(@RequestBody RegisterUserVo authenticationRequest)
            throws Exception {
        User user = userDetailsService.saveUser(authenticationRequest);
        if (Objects.nonNull(user)) {
            return ResponseEntity.ok(String.format("User %s successfully created", user.getUsername()));
        }
        return ResponseEntity.ok("User not created");
    }

    private void authenticate(String username, String password) throws Exception {
        try {
            authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(username, password));
        } catch (DisabledException e) {
            throw new Exception("USER_DISABLED", e);
        } catch (BadCredentialsException e) {
            throw new Exception("INVALID_CREDENTIALS", e);
        }
    }
    
    private String extractDeviceFromUserAgent(String userAgent) {
        if (userAgent == null || userAgent.isEmpty()) {
            return "Unknown-Device";
        }
        
        String ua = userAgent.toLowerCase();
        if (ua.contains("mobile") || ua.contains("android") || ua.contains("iphone")) {
            return "Mobile-Device";
        } else if (ua.contains("chrome")) {
            return "Desktop-Chrome";
        } else if (ua.contains("firefox")) {
            return "Desktop-Firefox";
        } else if (ua.contains("safari")) {
            return "Desktop-Safari";
        } else {
            return "Desktop-Browser";
        }
    }
}
