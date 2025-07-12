package com.mangle.retailshopapp.customer.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mangle.retailshopapp.customer.model.CustomerTripLedger;
public interface CustomerTripLedgerRepository extends JpaRepository<CustomerTripLedger,Integer> {
    
    @Query("SELECT c FROM CustomerTripLedger c WHERE c.custId = :custId AND c.tripDateTime > "+
    "COALESCE((SELECT MAX(sub.tripDateTime) FROM CustomerTripLedger sub WHERE sub.custId = :custId AND sub.balanceAmount = 0), '1970-01-01T00:00:00') ORDER BY c.tripDateTime DESC") 
    List<CustomerTripLedger> findLatestTransactionsAfterZeroBalance(@Param("custId") Integer customerId);    Page<CustomerTripLedger> findByCustId(int custId, Pageable pageable);

    @Query(value = "SELECT c FROM CustomerTripLedger c WHERE c.custId = :custId AND c.status = com.mangle.retailshopapp.water.model.TripStatus.FILLING ORDER BY c.tripDateTime DESC", nativeQuery = false)
    Optional<CustomerTripLedger> findFirstInProgressTrip(@Param("custId") int custId);

    @Query(value = "SELECT c.custId FROM CustomerTripLedger c WHERE c.status = com.mangle.retailshopapp.water.model.TripStatus.FILLING")
    Optional<Integer> findCustomerWithInProgressTrip();
}

