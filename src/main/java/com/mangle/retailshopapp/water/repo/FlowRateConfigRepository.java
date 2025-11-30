package com.mangle.retailshopapp.water.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mangle.retailshopapp.water.model.FlowRateConfig;

public interface FlowRateConfigRepository extends JpaRepository<FlowRateConfig, Integer> {
    
    @Query("SELECT c FROM FlowRateConfig c " +
           "WHERE c.pumpType = :pumpType " +
           "AND c.isActive = true " +
           "AND (c.customerId = :customerId OR c.customerId IS NULL) " +
           "ORDER BY c.customerId DESC NULLS LAST")
    List<FlowRateConfig> findActiveConfigs(
        @Param("pumpType") String pumpType,
        @Param("customerId") Integer customerId
    );
    
    Optional<FlowRateConfig> findByPumpTypeAndCustomerIdAndIsActive(
        String pumpType, Integer customerId, Boolean isActive
    );
}

