package com.mangle.retailshopapp.audit.service;

import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.mangle.retailshopapp.water.model.AuditLog;
import com.mangle.retailshopapp.water.repo.AuditLogRepository;

@Service
public class AuditLogService {
    
    @Autowired
    private AuditLogRepository auditLogRepository;
    
    public void logPumpAction(int userId, String action, String pumpType, 
                              Integer waterPartyId, boolean isChargeable, String ipAddress) {
        AuditLog log = new AuditLog();
        log.setUserId(userId);
        log.setActionType("PUMP_" + action.toUpperCase());
        log.setActionDescription(String.format("Pump %s %s", pumpType, action));
        log.setTargetEntity("PUMP");
        log.setPerformedBy(userId);
        log.setPerformedForWaterPartyId(waterPartyId);
        log.setChargeableAction(isChargeable);
        log.setIpAddress(ipAddress);
        log.setTimestamp(LocalDateTime.now());
        
        auditLogRepository.save(log);
    }
    
    public void logLogin(int userId, String ipAddress) {
        AuditLog log = new AuditLog();
        log.setUserId(userId);
        log.setActionType("LOGIN");
        log.setActionDescription("User login");
        log.setTargetEntity("USER");
        log.setTargetId(userId);
        log.setPerformedBy(userId);
        log.setIpAddress(ipAddress);
        log.setTimestamp(LocalDateTime.now());
        
        auditLogRepository.save(log);
    }
    
    public void logApproval(int adminId, int customerId, String action, String ipAddress) {
        AuditLog log = new AuditLog();
        log.setUserId(customerId);
        log.setActionType("CUSTOMER_" + action.toUpperCase());
        log.setActionDescription(String.format("Customer %s by admin", action.toLowerCase()));
        log.setTargetEntity("CUSTOMER");
        log.setTargetId(customerId);
        log.setPerformedBy(adminId);
        log.setIpAddress(ipAddress);
        log.setTimestamp(LocalDateTime.now());
        
        auditLogRepository.save(log);
    }
}

