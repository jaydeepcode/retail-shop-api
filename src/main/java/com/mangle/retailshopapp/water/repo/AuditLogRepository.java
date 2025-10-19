package com.mangle.retailshopapp.water.repo;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.mangle.retailshopapp.water.model.AuditLog;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    
    List<AuditLog> findByUserIdOrderByTimestampDesc(int userId);
    
    List<AuditLog> findByPerformedForWaterPartyIdOrderByTimestampDesc(Integer waterPartyId);
    
    @Query("SELECT a FROM AuditLog a WHERE a.timestamp BETWEEN ?1 AND ?2 ORDER BY a.timestamp DESC")
    List<AuditLog> findByTimestampBetweenOrderByTimestampDesc(LocalDateTime start, LocalDateTime end);
    
    @Query("SELECT a FROM AuditLog a WHERE a.actionType = ?1 ORDER BY a.timestamp DESC")
    List<AuditLog> findByActionTypeOrderByTimestampDesc(String actionType);
}
