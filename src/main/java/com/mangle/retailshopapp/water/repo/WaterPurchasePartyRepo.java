package com.mangle.retailshopapp.water.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mangle.retailshopapp.water.model.WaterPurchaseParty;

public interface WaterPurchasePartyRepo extends JpaRepository<WaterPurchaseParty, Long> {
    @Query("select w from WaterPurchaseParty w where w.customerId = :customerId")
    WaterPurchaseParty findPartyDetailsByCustomerId(@Param("customerId") Integer customerId);
}
