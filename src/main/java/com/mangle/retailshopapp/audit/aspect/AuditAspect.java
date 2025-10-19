package com.mangle.retailshopapp.audit.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.mangle.retailshopapp.audit.annotation.Auditable;
import com.mangle.retailshopapp.audit.service.AuditLogService;

@Aspect
@Component
public class AuditAspect {
    
    @Autowired
    private AuditLogService auditLogService;
    
    @Around("@annotation(auditable)")
    public Object auditMethod(ProceedingJoinPoint joinPoint, Auditable auditable) throws Throwable {
        // Extract user from SecurityContext
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        
        if (auth != null && auth.isAuthenticated()) {
            // Get user ID from authentication
            String username = auth.getName();
            
            // For now, we'll log basic method execution
            // In a full implementation, you'd extract more details from method parameters
            
            Object result = joinPoint.proceed();
            
            // Log the action (simplified for now)
            // In production, you'd extract more context from the method call
            
            return result;
        }
        
        return joinPoint.proceed();
    }
}

