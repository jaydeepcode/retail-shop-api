package com.mangle.retailshopapp.user.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import com.mangle.retailshopapp.user.comp.JwtUtil;
import com.mangle.retailshopapp.user.model.RetailAppUser;
import com.mangle.retailshopapp.user.model.User;
import com.mangle.retailshopapp.user.model.UserApiKey;
import com.mangle.retailshopapp.user.repo.UserApiKeyRepository;

@Service
public class ApiKeyService {
    
    @Autowired
    private UserApiKeyRepository apiKeyRepository;
    
    @Autowired
    private JwtUtil jwtUtil;
    
    public String generateApiKey(User user, Integer waterPartyId) {
        return generateApiKey(user, waterPartyId, null);
    }
    
    public String generateApiKey(User user, Integer waterPartyId, String deviceInfo) {
        // Normalize device info (use provided or extract from User-Agent as fallback)
        String normalizedDeviceInfo = normalizeDeviceInfo(deviceInfo);
        
        // Check if user already has active key for same device created within last 7 days
        if (normalizedDeviceInfo != null) {
            LocalDateTime sevenDaysAgo = LocalDateTime.now().minusDays(7);
            UserApiKey existingKey = apiKeyRepository.findByUserIdAndDeviceInfoAndIsActive(
                user.getId(), normalizedDeviceInfo, true).orElse(null);
            
            if (existingKey != null && existingKey.getCreatedDate().isAfter(sevenDaysAgo) 
                && existingKey.getExpiryDate().isAfter(LocalDateTime.now())) {
                // Return existing key if it's still valid and created within 7 days
                return existingKey.getApiKey();
            }
        }
        
        // Role-based key management for new key generation:
        // - ADMIN users: Allow multiple active keys (PC + Mobile access)
        // - CUSTOMER users: Single device security (revoke old keys for different devices)
        if (!user.getRoles().contains("ROLE_ADMIN")) {
            // Customer users: Single device (revoke old keys for security)
            revokeAllUserKeys(user.getId());
        } else {
            // Admin users: Only revoke keys for same device, keep other device keys active
            if (normalizedDeviceInfo != null) {
                revokeKeysForDevice(user.getId(), normalizedDeviceInfo);
            }
        }
        
        // Generate JWT-based API key
        UserDetails userDetails = new RetailAppUser(user);
        String apiKeyToken = jwtUtil.generateApiKey(userDetails, waterPartyId);
        
        // Store in database
        UserApiKey apiKey = new UserApiKey();
        apiKey.setUserId(user.getId());
        apiKey.setApiKey(apiKeyToken);
        apiKey.setWaterPartyId(waterPartyId);
        apiKey.setCreatedDate(LocalDateTime.now());
        apiKey.setExpiryDate(LocalDateTime.now().plusDays(30));
        apiKey.setActive(true);
        apiKey.setDeviceInfo(normalizedDeviceInfo);
        
        apiKeyRepository.save(apiKey);
        return apiKeyToken;
    }
    
    public UserApiKey validateApiKey(String apiKey) {
        return apiKeyRepository.findByApiKeyAndIsActive(apiKey, true)
                .orElseThrow(() -> new RuntimeException("Invalid API key"));
    }
    
    public void revokeApiKey(String apiKey) {
        apiKeyRepository.findByApiKey(apiKey).ifPresent(key -> {
            key.setActive(false);
            apiKeyRepository.save(key);
        });
    }
    
    public void revokeAllUserKeys(int userId) {
        List<UserApiKey> userKeys = apiKeyRepository.findByUserIdAndIsActive(userId, true);
        userKeys.forEach(key -> {
            key.setActive(false);
            apiKeyRepository.save(key);
        });
    }
    
    public void revokeKeysForDevice(int userId, String deviceInfo) {
        List<UserApiKey> userKeys = apiKeyRepository.findByUserIdAndIsActive(userId, true);
        userKeys.stream()
            .filter(key -> deviceInfo.equals(key.getDeviceInfo()))
            .forEach(key -> {
                key.setActive(false);
                apiKeyRepository.save(key);
            });
    }
    
    private String normalizeDeviceInfo(String deviceInfo) {
        if (deviceInfo != null && !deviceInfo.trim().isEmpty()) {
            return deviceInfo.trim();
        }
        return null;
    }
    
    
    @Scheduled(cron = "0 0 21 * * *") // Run daily at 9 PM
    public void cleanupExpiredKeys() {
        LocalDateTime now = LocalDateTime.now();
        apiKeyRepository.deleteByExpiryDateBeforeAndIsActive(now, false);
    }
}
