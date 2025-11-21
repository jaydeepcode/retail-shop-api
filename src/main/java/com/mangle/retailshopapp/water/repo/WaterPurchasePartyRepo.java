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
    
    @Query("SELECT new com.mangle.retailshopapp.water.model.WaterPurchasePartyWithNameDTO(" +
           "w.id, w.customerId, w.storageTypeCode, " +
           "CASE w.storageTypeCode " +
           "    WHEN 'TANKER' THEN 'Tanker' " +
           "    WHEN 'SMALL_PURCHASE' THEN 'Small Purchase' " +
           "    ELSE 'Unknown' " +
           "END, " +
           "w.capacity, w.address, c.userId, " +
           "COALESCE(u.accountStatus, c.statusCode, 'PENDING'), w.contactNumber, " +
           "CONCAT(c.firstName, ' ', c.lastName)) " +
           "FROM WaterPurchaseParty w " +
           "JOIN CustomerDetails c ON w.customerId = c.custId " +
           "LEFT JOIN User u ON u.id = c.userId " +
           "WHERE COALESCE(u.accountStatus, c.statusCode, 'PENDING') = 'PENDING'")
    List<WaterPurchasePartyWithNameDTO> findPendingCustomersWithNames();
    
    @Query("SELECT new com.mangle.retailshopapp.water.model.WaterPurchasePartyWithNameDTO(" +
           "w.id, w.customerId, w.storageTypeCode, " +
           "CASE w.storageTypeCode " +
           "    WHEN 'TANKER' THEN 'Tanker' " +
           "    WHEN 'SMALL_PURCHASE' THEN 'Small Purchase' " +
           "    ELSE 'Unknown' " +
           "END, " +
           "w.capacity, w.address, c.userId, " +
           "COALESCE(u.accountStatus, c.statusCode, 'PENDING'), w.contactNumber, " +
           "CONCAT(c.firstName, ' ', c.lastName)) " +
           "FROM WaterPurchaseParty w " +
           "JOIN CustomerDetails c ON w.customerId = c.custId " +
           "LEFT JOIN CustomerTripLedger t ON w.customerId = t.custId " +
           "LEFT JOIN User u ON u.id = c.userId " +
           "WHERE COALESCE(u.accountStatus, c.statusCode, 'PENDING') IN ('APPROVED', 'ACTIVE') " +
           "AND w.isActive = true " +
           "GROUP BY w.id, w.customerId, w.storageTypeCode, w.capacity, w.address, c.userId, " +
           "COALESCE(u.accountStatus, c.statusCode, 'PENDING'), w.contactNumber, c.firstName, c.lastName " +
           "ORDER BY MAX(t.tripDateTime) DESC, COUNT(t.id) DESC " +
           "LIMIT 10")
    List<WaterPurchasePartyWithNameDTO> findApprovedCustomersWithNames();
}
