package com.mangle.retailshopapp.water.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mangle.retailshopapp.water.model.WaterPurchaseParty;
import com.mangle.retailshopapp.water.model.WaterPurchasePartyWithNameDTO;

public interface WaterPurchasePartyRepo extends JpaRepository<WaterPurchaseParty, Long> {
    @Query("select w from WaterPurchaseParty w where w.customerId = :customerId")
    Optional<WaterPurchaseParty> findPartyDetailsByCustomerId(@Param("customerId") Integer customerId);
    
    boolean existsByVehicleNumber(String vehicleNumber);
    
    Optional<WaterPurchaseParty> findByVehicleNumber(String vehicleNumber);
    
    // New queries for enhanced functionality
    @Query("SELECT w FROM WaterPurchaseParty w JOIN CustomerDetails c ON w.customerId = c.custId WHERE c.userId = :userId")
    Optional<WaterPurchaseParty> findByCustomerIdAndUserId(@Param("userId") Integer userId);
    
    List<WaterPurchaseParty> findByRegistrationStatus(String registrationStatus);
    
    List<WaterPurchaseParty> findByRegistrationStatusAndIsActive(String registrationStatus, boolean isActive);
    
    @Query("SELECT w FROM WaterPurchaseParty w WHERE w.registrationStatus = 'APPROVED' AND w.isActive = true")
    List<WaterPurchaseParty> findApprovedAndActiveCustomers();
    
    // New queries that join with CustomerDetails table to get customer names
    @Query("SELECT new com.mangle.retailshopapp.water.model.WaterPurchasePartyWithNameDTO(" +
           "w.id, w.customerId, w.storageType, w.capacity, w.address, c.userId, " +
           "w.registrationStatus, w.contactNumber, " +
           "CONCAT(c.firstName, ' ', c.lastName)) " +
           "FROM WaterPurchaseParty w " +
           "JOIN CustomerDetails c ON w.customerId = c.custId " +
           "WHERE w.registrationStatus = 'PENDING' AND c.userId IS NOT NULL")
    List<WaterPurchasePartyWithNameDTO> findPendingCustomersWithNames();
    
    @Query("SELECT new com.mangle.retailshopapp.water.model.WaterPurchasePartyWithNameDTO(" +
           "w.id, w.customerId, w.storageType, w.capacity, w.address, c.userId, " +
           "w.registrationStatus, w.contactNumber, " +
           "CONCAT(c.firstName, ' ', c.lastName)) " +
           "FROM WaterPurchaseParty w " +
           "JOIN CustomerDetails c ON w.customerId = c.custId " +
           "LEFT JOIN CustomerTripLedger t ON w.customerId = t.custId " +
           "WHERE (w.registrationStatus = 'APPROVED' AND w.isActive = true) OR c.userId IS NULL " +
           "GROUP BY w.id, w.customerId, w.storageType, w.capacity, w.address, c.userId, " +
           "w.registrationStatus, w.contactNumber, c.firstName, c.lastName " +
           "ORDER BY MAX(t.tripDateTime) DESC, COUNT(t.id) DESC " +
           "LIMIT 10")
    List<WaterPurchasePartyWithNameDTO> findApprovedCustomersWithNames();
}
