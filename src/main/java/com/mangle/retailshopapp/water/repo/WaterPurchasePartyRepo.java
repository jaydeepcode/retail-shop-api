package com.mangle.retailshopapp.water.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mangle.retailshopapp.water.model.WaterPurchaseParty;

public interface WaterPurchasePartyRepo extends JpaRepository<WaterPurchaseParty, Long> {
    @Query("select w from WaterPurchaseParty w where w.customerId = :customerId")
    WaterPurchaseParty findPartyDetailsByCustomerId(@Param("customerId") Integer customerId);
    
    // New queries for enhanced functionality
    Optional<WaterPurchaseParty> findByUserId(Integer userId);
    
    List<WaterPurchaseParty> findByRegistrationStatus(String registrationStatus);
    
    List<WaterPurchaseParty> findByRegistrationStatusAndIsActive(String registrationStatus, boolean isActive);
    
    @Query("SELECT w FROM WaterPurchaseParty w WHERE w.registrationStatus = 'APPROVED' AND w.isActive = true")
    List<WaterPurchaseParty> findApprovedAndActiveCustomers();
}
