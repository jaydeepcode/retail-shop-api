package com.mangle.retailshopapp.customer.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

import com.mangle.retailshopapp.customer.model.CustomerTripLedger;
import com.mangle.retailshopapp.water.model.PumpUsed;
import com.mangle.retailshopapp.water.model.TripStatus;

public interface CustomerTripLedgerRepository extends JpaRepository<CustomerTripLedger,Integer> {
    
    @Query("SELECT c FROM CustomerTripLedger c WHERE c.custId = :custId AND c.tripDateTime > "+
    "COALESCE((SELECT MAX(sub.tripDateTime) FROM CustomerTripLedger sub WHERE sub.custId = :custId AND sub.balanceAmount = 0), '1970-01-01T00:00:00') ORDER BY c.tripDateTime DESC") 
    List<CustomerTripLedger> findLatestTransactionsAfterZeroBalance(@Param("custId") Integer customerId);
    
    Page<CustomerTripLedger> findByCustId(int custId, Pageable pageable);

    @Query(value = "SELECT c FROM CustomerTripLedger c WHERE c.custId = :custId AND c.status = com.mangle.retailshopapp.water.model.TripStatus.FILLING ORDER BY c.tripDateTime DESC", nativeQuery = false)
    Optional<CustomerTripLedger> findFirstInProgressTrip(@Param("custId") int custId);

    @Query(value = "SELECT c.custId FROM CustomerTripLedger c WHERE c.status = com.mangle.retailshopapp.water.model.TripStatus.FILLING")
    Optional<Integer> findCustomerWithInProgressTrip();
    
    /**
     * Find trips that should be auto-stopped
     * Uses database-level filtering for performance
     * Uses native query for TIMESTAMPDIFF (MySQL-specific function)
     */
    @Query(value = "SELECT t.* FROM wt_purchase_details t " +
           "WHERE t.STATUS_CODE = 'FILLING' " +
           "AND t.auto_stop_scheduled = TRUE " +
           "AND t.auto_stopped = FALSE " +
           "AND t.expected_duration_sec IS NOT NULL " +
           "AND TIMESTAMPDIFF(SECOND, t.start_time, :now) >= t.expected_duration_sec",
           nativeQuery = true)
    List<CustomerTripLedger> findOverdueFillingTrips(@Param("now") LocalDateTime now);

    /**
     * Find completed trips for flow rate calculation
     */
    @Query("SELECT t FROM CustomerTripLedger t " +
           "WHERE t.custId = :customerId " +
           "AND t.pumpUsed = :pumpType " +
           "AND t.status = 'COMPLETED' " +
           "AND t.endTime >= :cutoffDate " +
           "AND t.endTime IS NOT NULL " +
           "ORDER BY t.endTime DESC")
    List<CustomerTripLedger> findCompletedTripsForFlowRate(
        @Param("customerId") Integer customerId,
        @Param("pumpType") PumpUsed pumpType,
        @Param("cutoffDate") LocalDateTime cutoffDate,
        Pageable pageable
    );

    /**
     * Global flow rate calculation
     */
    @Query("SELECT t FROM CustomerTripLedger t " +
           "WHERE t.pumpUsed = :pumpType " +
           "AND t.status = 'COMPLETED' " +
           "AND t.endTime >= :cutoffDate " +
           "AND t.endTime IS NOT NULL " +
           "ORDER BY t.endTime DESC")
    List<CustomerTripLedger> findCompletedTripsForFlowRateGlobal(
        @Param("pumpType") PumpUsed pumpType,
        @Param("cutoffDate") LocalDateTime cutoffDate,
        Pageable pageable
    );

    /**
     * Find recently completed trips for outlier detection
     */
    @Query("SELECT t FROM CustomerTripLedger t " +
           "WHERE t.status = 'COMPLETED' " +
           "AND t.endTime >= :since " +
           "ORDER BY t.endTime DESC")
    List<CustomerTripLedger> findRecentlyCompletedTrips(@Param("since") LocalDateTime since);
    
    /**
     * Find active trips with auto-stop scheduled (for system recovery)
     */
    List<CustomerTripLedger> findByStatusAndAutoStopScheduled(TripStatus status, Boolean autoStopScheduled);
    
    /**
     * Find completed trips by capacity and pump type (for global average)
     */
    @Query("""
        SELECT t FROM CustomerTripLedger t
        JOIN com.mangle.retailshopapp.water.model.WaterPurchaseParty p ON p.customerId = t.custId
        WHERE p.capacity = :capacity
        AND t.pumpUsed = :pumpType
        AND t.status = 'COMPLETED'
        AND t.startTime >= :cutoffDate
        AND t.endTime IS NOT NULL
        ORDER BY t.endTime DESC
        """)
    List<CustomerTripLedger> findCompletedTripsForFlowRateByCapacity(
        @Param("capacity") Integer capacity,
        @Param("pumpType") PumpUsed pumpType,
        @Param("cutoffDate") LocalDateTime cutoffDate,
        Pageable pageable
    );
    
    /**
     * Find distinct customer IDs with activity since date
     */
    @Query("""
        SELECT DISTINCT t.custId FROM CustomerTripLedger t
        WHERE t.tripDateTime >= :since
        """)
    List<Integer> findDistinctCustomerIdsSince(@Param("since") LocalDateTime since);
    
    /**
     * Find distinct pump types used by customer
     */
    @Query("""
        SELECT DISTINCT t.pumpUsed FROM CustomerTripLedger t
        WHERE t.custId = :customerId
        AND t.tripDateTime >= :since
        """)
    List<PumpUsed> findDistinctPumpTypesByCustomer(
        @Param("customerId") Integer customerId,
        @Param("since") LocalDateTime since
    );
}

