package com.mangle.retailshopapp.water.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.mangle.retailshopapp.customer.model.CustomerTripLedger;
import com.mangle.retailshopapp.customer.repo.CustomerTripLedgerRepository;
import com.mangle.retailshopapp.water.model.FlowRateCache;
import com.mangle.retailshopapp.water.model.FlowRateCacheId;
import com.mangle.retailshopapp.water.model.FlowRateConfig;
import com.mangle.retailshopapp.water.model.FlowRateDTO;
import com.mangle.retailshopapp.water.model.PumpUsed;
import com.mangle.retailshopapp.water.model.TripFlowRateAnomaly;
import com.mangle.retailshopapp.water.model.WaterPurchaseParty;
import com.mangle.retailshopapp.water.repo.FlowRateCacheRepository;
import com.mangle.retailshopapp.water.repo.FlowRateConfigRepository;
import com.mangle.retailshopapp.water.repo.TripAnomalyRepository;
import com.mangle.retailshopapp.water.repo.WaterPurchasePartyRepo;

@Service
public class FlowRateService {
    
    private static final Logger logger = LoggerFactory.getLogger(FlowRateService.class);
    
    @Autowired
    private CustomerTripLedgerRepository tripRepo;
    
    @Autowired
    private WaterPurchasePartyRepo partyRepo;
    
    @Autowired
    private FlowRateConfigRepository configRepo;
    
    @Autowired
    private FlowRateCacheRepository cacheRepo;
    
    @Autowired
    private TripAnomalyRepository anomalyRepo;
    
    /**
     * Main entry point - gets flow rate with caching
     * NOTE: This method may trigger expensive calculations.
     * Use getFlowRateForEstimation() for fast retrieval in API endpoints.
     */
    public FlowRateDTO getFlowRate(Integer customerId, PumpUsed pumpType) {
        // Check cache first
        FlowRateCacheId cacheId = new FlowRateCacheId(customerId, pumpType.name());
        Optional<FlowRateCache> cached = cacheRepo.findById(cacheId);
        
        if (cached.isPresent() && !isCacheStale(cached.get())) {
            logger.debug("Returning cached flow rate for customer {} pump {}", 
                customerId, pumpType);
            return toDTO(cached.get());
        }
        
        // Calculate and cache
        return calculateAndCache(customerId, pumpType);
    }
    
    /**
     * Fast retrieval for estimatedTime API - NEVER calculates.
     * Returns cached value or falls back to config default.
     * Guaranteed <100ms response time.
     */
    public FlowRateDTO getFlowRateForEstimation(Integer customerId, PumpUsed pumpType) {
        // Check cache first
        FlowRateCacheId cacheId = new FlowRateCacheId(customerId, pumpType.name());
        Optional<FlowRateCache> cached = cacheRepo.findById(cacheId);
        
        if (cached.isPresent()) {
            logger.debug("Cache hit for customer {} pump {}", customerId, pumpType);
            return toDTO(cached.get());
        }
        
        // Cache miss - fallback to config default (NO CALCULATION)
        logger.info("Cache miss for customer {} pump {}, using config default", 
            customerId, pumpType);
        FlowRateConfig config = getActiveConfig(customerId, pumpType);
        
        FlowRateDTO dto = new FlowRateDTO();
        dto.setSecPerLiter(config.getDefaultSecPerLiter());
        dto.setPumpType(pumpType.name());
        dto.setSampleSize(0);  // sampleSize = 0 indicates config default
        dto.setCalculationSource("CONFIG_DEFAULT");
        dto.setLastCalculated(LocalDateTime.now().toString());
        
        return dto;
    }
    
    /**
     * Calculate flow rate from historical trips
     * Priority: Customer history → Global average by capacity → Config default
     */
    private FlowRateDTO calculateAndCache(Integer customerId, PumpUsed pumpType) {
        // Get applicable config (customer-specific or global)
        FlowRateConfig config = getActiveConfig(customerId, pumpType);
        
        // Smart cutoff: use config.effectiveFrom only if within last 6 months
        LocalDateTime configCutoff = config.getEffectiveFrom();
        LocalDateTime sixMonthsAgo = LocalDateTime.now().minusMonths(6);
        LocalDateTime cutoffDate = configCutoff.isAfter(sixMonthsAgo) 
            ? configCutoff 
            : sixMonthsAgo;
        
        logger.debug("Using cutoff date {} (config: {}, 6mo ago: {})",
            cutoffDate, configCutoff, sixMonthsAgo);
        
        // Get customer's capacity
        Optional<WaterPurchaseParty> partyOpt = partyRepo.findPartyDetailsByCustomerId(customerId);
        if (partyOpt.isEmpty()) {
            logger.warn("No party contract for customer {}, using config default", customerId);
            return cacheAndReturn(customerId, pumpType, 
                config.getDefaultSecPerLiter(), 0, "CONFIG_DEFAULT");
        }
        
        WaterPurchaseParty party = partyOpt.get();
        BigDecimal configDefault = config.getDefaultSecPerLiter();
        
        // Try customer-specific history first (5 trips)
        List<BigDecimal> customerRates = calculateCustomerRates(
            customerId, pumpType, configDefault, party.getCapacity(), cutoffDate
        );
        
        if (customerRates.size() >= 5) {
            BigDecimal avgRate = customerRates.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(customerRates.size()), 3, RoundingMode.HALF_UP);
            
            logger.info("Customer {} using CUSTOMER_HISTORY: {} sec/L from {} trips",
                customerId, avgRate, customerRates.size());
            
            return cacheAndReturn(customerId, pumpType, avgRate, 
                customerRates.size(), "CUSTOMER_HISTORY");
        }
        
        // Fallback to global average by capacity (30 trips)
        List<BigDecimal> globalRates = calculateGlobalRatesByCapacity(
            party.getCapacity(), pumpType, cutoffDate, configDefault
        );
        
        if (globalRates.size() >= 30) {
            BigDecimal avgRate = globalRates.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(globalRates.size()), 3, RoundingMode.HALF_UP);
            
            logger.info("Customer {} using GLOBAL_AVG for {}L capacity: {} sec/L from {} trips",
                customerId, party.getCapacity(), avgRate, globalRates.size());
            
            return cacheAndReturn(customerId, pumpType, avgRate, 
                globalRates.size(), "GLOBAL_AVG");
        }
        
        // Fallback to config default
        logger.info("Customer {} using CONFIG_DEFAULT: {} sec/L (insufficient data)",
            customerId, configDefault);
        
        return cacheAndReturn(customerId, pumpType, 
            configDefault, 0, "CONFIG_DEFAULT");
    }
    
    /**
     * Calculate customer-specific flow rate with ±10% validation.
     * Requires 5 valid trips after filtering.
     */
    private List<BigDecimal> calculateCustomerRates(
        Integer customerId,
        PumpUsed pumpType,
        BigDecimal configDefault,
        Integer capacity,
        LocalDateTime cutoffDate
    ) {
        // Fetch last 5 completed trips (not 10)
        Pageable pageable = PageRequest.of(0, 5);
        List<CustomerTripLedger> trips = tripRepo.findCompletedTripsForFlowRate(
            customerId, pumpType, cutoffDate, pageable
        );
        
        List<BigDecimal> validRates = new ArrayList<>();
        
        for (CustomerTripLedger trip : trips) {
            if (trip.getStartTime() == null || trip.getEndTime() == null) {
                continue;
            }
            
            long durationSec = Duration.between(
                trip.getStartTime(), trip.getEndTime()
            ).getSeconds();
            
            if (durationSec <= 0 || capacity <= 0) {
                continue;
            }
            
            BigDecimal secPerLiter = BigDecimal.valueOf(durationSec)
                .divide(BigDecimal.valueOf(capacity), 3, RoundingMode.HALF_UP);
            
            // Filter: within ±10% of configDefault
            if (isWithinTenPercent(secPerLiter, configDefault)) {
                validRates.add(secPerLiter);
            } else {
                logger.debug("Filtered outlier trip {}: {} sec/L (expected ~{} sec/L)",
                    trip.getId(), secPerLiter, configDefault);
            }
        }
        
        return validRates;
    }
    
    /**
     * Calculate global average flow rate by CAPACITY (not pump type alone).
     * Requires 30 valid trips after ±10% filtering.
     */
    private List<BigDecimal> calculateGlobalRatesByCapacity(
        Integer capacity,
        PumpUsed pumpType,
        LocalDateTime cutoffDate,
        BigDecimal configDefault
    ) {
        // Fetch trips with matching capacity (add to repository query)
        Pageable pageable = PageRequest.of(0, 100);
        List<CustomerTripLedger> trips = tripRepo.findCompletedTripsForFlowRateByCapacity(
            capacity, pumpType, cutoffDate, pageable
        );
        
        if (trips.isEmpty()) {
            return new ArrayList<>();
        }
        
        // Batch-fetch party details (fix N+1 query problem)
        List<Integer> customerIds = trips.stream()
            .map(CustomerTripLedger::getCustId)
            .distinct()
            .collect(Collectors.toList());
        
        List<WaterPurchaseParty> parties = partyRepo.findByCustomerIdIn(customerIds);
        
        // Create map for O(1) lookup
        Map<Integer, Integer> customerCapacityMap = parties.stream()
            .collect(Collectors.toMap(
                WaterPurchaseParty::getCustomerId,
                WaterPurchaseParty::getCapacity
            ));
        
        List<BigDecimal> validRates = new ArrayList<>();
        
        for (CustomerTripLedger trip : trips) {
            try {
                if (trip.getStartTime() == null || trip.getEndTime() == null) {
                    continue;
                }
                
                Integer tripCapacity = customerCapacityMap.get(trip.getCustId());
                if (tripCapacity == null || tripCapacity <= 0) {
                    continue;
                }
                
                long durationSec = Duration.between(
                    trip.getStartTime(), trip.getEndTime()
                ).getSeconds();
                
                if (durationSec <= 0) {
                    continue;
                }
                
                BigDecimal secPerLiter = BigDecimal.valueOf(durationSec)
                    .divide(BigDecimal.valueOf(tripCapacity), 3, RoundingMode.HALF_UP);
                
                // Filter: within ±10% of configDefault
                if (isWithinTenPercent(secPerLiter, configDefault)) {
                    validRates.add(secPerLiter);
                }
                
            } catch (Exception e) {
                logger.warn("Failed to calculate flow rate for trip {}: {}", 
                    trip.getId(), e.getMessage());
            }
        }
        
        logger.info("Global average by capacity {}: {} valid rates from {} trips",
            capacity, validRates.size(), trips.size());
        
        return validRates;
    }
    
    /**
     * Helper: Check if measured value is within ±10% of baseline
     */
    private boolean isWithinTenPercent(BigDecimal measured, BigDecimal baseline) {
        if (baseline.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        
        BigDecimal difference = measured.subtract(baseline).abs();
        BigDecimal deviationPercent = difference
            .divide(baseline, 2, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100));
        
        return deviationPercent.compareTo(BigDecimal.valueOf(10)) <= 0;
    }
    
    /**
     * Background job: Process recently completed trips for outlier detection
     * Runs every 10 minutes
     */
    @Scheduled(fixedDelay = 600000) // 10 minutes
    public void processRecentTrips() {
        LocalDateTime since = LocalDateTime.now().minusMinutes(10);
        
        List<CustomerTripLedger> recentTrips = tripRepo.findRecentlyCompletedTrips(since);
        
        if (!recentTrips.isEmpty()) {
            logger.info("Processing {} recent trips for flow rate validation", 
                recentTrips.size());
        }
        
        for (CustomerTripLedger trip : recentTrips) {
            try {
                validateAndFlagOutliers(trip);
            } catch (Exception e) {
                logger.error("Failed to process trip {}: {}", 
                    trip.getId(), e.getMessage());
            }
        }
    }
    
    /**
     * Outlier detection: Compare against customer's rolling average
     * Threshold: ±10% deviation
     */
    private void validateAndFlagOutliers(CustomerTripLedger trip) {
        Optional<WaterPurchaseParty> partyOpt = partyRepo
            .findPartyDetailsByCustomerId(trip.getCustId());
        
        if (partyOpt.isEmpty() || trip.getStartTime() == null || 
            trip.getEndTime() == null) {
            return;
        }
        
        WaterPurchaseParty party = partyOpt.get();
        long durationSec = Duration.between(
            trip.getStartTime(), trip.getEndTime()
        ).getSeconds();
        
        if (durationSec <= 0) {
            flagAnomaly(trip, BigDecimal.ZERO, BigDecimal.ZERO, "DURATION_ZERO");
            return;
        }
        
        if (party.getCapacity() <= 0) {
            return;
        }
        
        BigDecimal measured = BigDecimal.valueOf(durationSec)
            .divide(BigDecimal.valueOf(party.getCapacity()), 3, RoundingMode.HALF_UP);
        
        // Get customer's historical average for comparison (Option B from discussion)
        FlowRateDTO customerAvg = getFlowRate(trip.getCustId(), trip.getPumpUsed());
        BigDecimal expected = customerAvg.getSecPerLiter();
        
        // Skip if using config default (no baseline yet)
        if ("CONFIG_DEFAULT".equals(customerAvg.getCalculationSource())) {
            return;
        }
        
        // Calculate deviation percentage
        BigDecimal deviationPercent = BigDecimal.ZERO;
        if (expected.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal difference = measured.subtract(expected).abs();
            deviationPercent = difference.divide(expected, 2, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        }
        
        // ±10% threshold
        if (deviationPercent.compareTo(BigDecimal.valueOf(10)) > 0) {
            String reason = measured.compareTo(expected) > 0 ? "TOO_SLOW" : "TOO_FAST";
            flagAnomaly(trip, measured, expected, reason);
            logger.warn("Outlier detected: Trip {} deviated {}% from expected", 
                trip.getId(), deviationPercent);
        }
    }
    
    private void flagAnomaly(CustomerTripLedger trip, BigDecimal measured, 
                             BigDecimal expected, String reason) {
        TripFlowRateAnomaly anomaly = new TripFlowRateAnomaly();
        anomaly.setTripId(trip.getId());
        anomaly.setCustomerId(trip.getCustId());
        anomaly.setMeasuredSecPerLiter(measured);
        anomaly.setExpectedSecPerLiter(expected);
        
        BigDecimal deviationPercent = BigDecimal.ZERO;
        if (expected.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal difference = measured.subtract(expected).abs();
            deviationPercent = difference.divide(expected, 2, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        }
        anomaly.setDeviationPercent(deviationPercent);
        anomaly.setReason(reason);
        anomaly.setFlaggedAt(LocalDateTime.now());
        
        anomalyRepo.save(anomaly);
    }
    
    /**
     * Async: Recalculate flow rate when trip completes.
     * Non-blocking - doesn't delay API response.
     */
    @Async
    @EventListener
    public void onTripCompleted(com.mangle.retailshopapp.water.event.TripAutoStopEvent event) {
        CustomerTripLedger trip = event.getTrip();
        
        try {
            logger.info("Background recalculation triggered for customer {} after trip {}",
                trip.getCustId(), trip.getId());
            
            // Trigger calculation in background
            calculateAndCache(trip.getCustId(), trip.getPumpUsed());
            
        } catch (Exception e) {
            logger.error("Background flow rate calculation failed for customer {}: {}",
                trip.getCustId(), e.getMessage());
            // Non-fatal - cache will be refreshed by scheduled job
        }
    }
    
    /**
     * Scheduled: Bulk recalculation for all active customers.
     * Runs every hour.
     */
    @Scheduled(cron = "0 0 */1 * * *") // Every hour at :00
    public void recalculateActiveCustomers() {
        LocalDateTime thirtyDaysAgo = LocalDateTime.now().minusDays(30);
        
        // Find customers with recent activity
        List<Integer> activeCustomerIds = tripRepo.findDistinctCustomerIdsSince(thirtyDaysAgo);
        
        logger.info("Scheduled flow rate recalculation: {} active customers", 
            activeCustomerIds.size());
        
        for (Integer customerId : activeCustomerIds) {
            try {
                // Get customer's pump types from recent trips
                List<PumpUsed> pumpTypes = tripRepo.findDistinctPumpTypesByCustomer(
                    customerId, thirtyDaysAgo
                );
                
                for (PumpUsed pumpType : pumpTypes) {
                    calculateAndCache(customerId, pumpType);
                }
                
            } catch (Exception e) {
                logger.error("Scheduled recalculation failed for customer {}: {}",
                    customerId, e.getMessage());
            }
        }
        
        logger.info("Scheduled flow rate recalculation completed");
    }
    
    /**
     * Get active config for customer and pump type
     * Priority: Customer-specific → Global
     */
    private FlowRateConfig getActiveConfig(Integer customerId, PumpUsed pumpType) {
        List<FlowRateConfig> configs = configRepo.findActiveConfigs(
            pumpType.name(), customerId
        );
        
        // First config is customer-specific (ordered by customerId DESC NULLS LAST)
        if (!configs.isEmpty()) {
            return configs.get(0);
        }
        
        // Fallback to default if somehow no config found
        logger.warn("No active config found for pump type {}, using defaults", pumpType);
        FlowRateConfig defaultConfig = new FlowRateConfig();
        defaultConfig.setPumpType(pumpType.name());
        defaultConfig.setDefaultSecPerLiter(
            pumpType == PumpUsed.BOTH 
                ? BigDecimal.valueOf(0.46) 
                : BigDecimal.valueOf(0.9));
        defaultConfig.setEffectiveFrom(LocalDateTime.now());
        return defaultConfig;
    }
    
    /**
     * Cache the calculated flow rate
     */
    private FlowRateDTO cacheAndReturn(Integer customerId, PumpUsed pumpType, 
                                       BigDecimal secPerLiter, int sampleCount,
                                       String calculationSource) {
        FlowRateCache cache = new FlowRateCache();
        cache.setCustomerId(customerId);
        cache.setPumpType(pumpType.name());
        cache.setCalculatedSecPerLiter(secPerLiter);
        cache.setSampleCount(sampleCount);
        cache.setLastCalculated(LocalDateTime.now());
        cache.setCalculationSource(calculationSource);
        
        cacheRepo.save(cache);
        
        FlowRateDTO dto = new FlowRateDTO();
        dto.setSecPerLiter(secPerLiter);
        dto.setPumpType(pumpType.name());
        dto.setSampleSize(sampleCount);
        dto.setCalculationSource(calculationSource);
        dto.setLastCalculated(cache.getLastCalculated().toString());
        
        return dto;
    }
    
    /**
     * Convert cache to DTO
     */
    private FlowRateDTO toDTO(FlowRateCache cache) {
        FlowRateDTO dto = new FlowRateDTO();
        dto.setSecPerLiter(cache.getCalculatedSecPerLiter());
        dto.setPumpType(cache.getPumpType());
        dto.setSampleSize(cache.getSampleCount());
        dto.setCalculationSource(cache.getCalculationSource());
        dto.setLastCalculated(cache.getLastCalculated().toString());
        return dto;
    }
    
    /**
     * Check if cache is stale (older than 1 hour)
     */
    private boolean isCacheStale(FlowRateCache cache) {
        return Duration.between(cache.getLastCalculated(), LocalDateTime.now())
            .toHours() >= 1;
    }
}

