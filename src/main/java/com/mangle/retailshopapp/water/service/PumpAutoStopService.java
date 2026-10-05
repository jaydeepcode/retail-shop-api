package com.mangle.retailshopapp.water.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import com.mangle.retailshopapp.customer.model.CustomerTripLedger;
import com.mangle.retailshopapp.customer.repo.CustomerTripLedgerRepository;
import com.mangle.retailshopapp.water.controller.MotorController;
import com.mangle.retailshopapp.water.event.TripAutoStopEvent;
import com.mangle.retailshopapp.water.model.PumpUsed;
import com.mangle.retailshopapp.water.model.TripStatus;

import jakarta.annotation.PostConstruct;

@Service
public class PumpAutoStopService {

    private static final Logger logger = LoggerFactory.getLogger(PumpAutoStopService.class);
    private static final int CHECK_BEFORE_COMPLETION_SECONDS = 5;

    @Autowired
    private CustomerTripLedgerRepository tripRepo;

    @Autowired
    private MotorController motorController;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TaskScheduler taskScheduler;

    // Track scheduled checks to allow cancellation
    private final Map<Integer, ScheduledFuture<?>> scheduledChecks = new ConcurrentHashMap<>();

    /**
     * Schedule a one-time check 5 seconds before expected completion
     * Cancels existing check if trip is restarted
     */
    public void scheduleAutoStopCheck(Integer tripId, LocalDateTime checkTime) {
        // Cancel existing check if any
        cancelScheduledCheck(tripId);

        LocalDateTime now = LocalDateTime.now();
        
        // If check time is in the past, check immediately
        if (checkTime.isBefore(now) || checkTime.isEqual(now)) {
            logger.info("Check time {} is in the past for trip {}, checking immediately", checkTime, tripId);
            checkAndStopTrip(tripId);
            return;
        }

        // Calculate delay in milliseconds
        Duration delay = Duration.between(now, checkTime);
        long delayMillis = delay.toMillis();

        // Schedule the check
        ScheduledFuture<?> future = taskScheduler.schedule(
            () -> checkAndStopTrip(tripId),
            Instant.now().plusMillis(delayMillis)
        );

        scheduledChecks.put(tripId, future);
        logger.debug("Scheduled auto-stop check for trip {} at {} ({} ms from now)", 
            tripId, checkTime, delayMillis);
    }

    /**
     * Check if trip has reached completion and stop if ready
     * Reschedules if not ready yet
     */
    public void checkAndStopTrip(Integer tripId) {
        Optional<CustomerTripLedger> tripOpt = tripRepo.findById(tripId);
        
        if (tripOpt.isEmpty()) {
            logger.warn("Trip {} not found, removing from scheduled checks", tripId);
            scheduledChecks.remove(tripId);
            return;
        }

        CustomerTripLedger trip = tripOpt.get();

        // Only process if still in FILLING status
        if (trip.getStatus() != TripStatus.FILLING) {
            logger.info("Trip {} is no longer in FILLING status ({}), removing from scheduled checks", 
                tripId, trip.getStatus());
            scheduledChecks.remove(tripId);
            return;
        }

        // Check if elapsed time >= expected duration
        if (trip.getStartTime() == null || trip.getExpectedDurationSec() == null) {
            logger.warn("Trip {} missing startTime or expectedDuration, cannot check", tripId);
            scheduledChecks.remove(tripId);
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        long elapsedSeconds = Duration.between(trip.getStartTime(), now).getSeconds();
        int expectedDuration = trip.getExpectedDurationSec();

        if (elapsedSeconds >= expectedDuration) {
            // Time to stop!
            try {
                autoStopPump(trip);
                scheduledChecks.remove(tripId);
            } catch (Exception e) {
                logger.error("Failed to auto-stop trip {}: {}", tripId, e.getMessage(), e);
                markAutoStopFailed(trip);
                scheduledChecks.remove(tripId);
            }
        } else {
            // Not ready yet, reschedule for when it should be ready
            int remainingSeconds = (int) (expectedDuration - elapsedSeconds);
            
            // Never schedule for "now" - always wait at least 1 second
            // If within 5 seconds of completion, just wait for actual remaining time
            int delaySeconds = Math.max(1, remainingSeconds);
            LocalDateTime newCheckTime = now.plusSeconds(delaySeconds);
            
            logger.debug("Trip {} not ready yet ({}s elapsed, {}s expected), rescheduling in {}s", 
                tripId, elapsedSeconds, expectedDuration, delaySeconds);
            scheduleAutoStopCheck(tripId, newCheckTime);
        }
    }

    /**
     * Cancel scheduled check when trip is manually stopped
     */
    public void cancelScheduledCheck(Integer tripId) {
        ScheduledFuture<?> future = scheduledChecks.remove(tripId);
        if (future != null && !future.isDone()) {
            future.cancel(false);
            logger.debug("Cancelled scheduled auto-stop check for trip {}", tripId);
        }
    }

    /**
     * Auto-stop a specific trip
     */
    private void autoStopPump(CustomerTripLedger trip) throws Exception {
        logger.info("Auto-stopping trip {} for customer {}",
                trip.getId(), trip.getCustId());

        try {
            // Stop physical pump hardware
            PumpUsed pumpUsed = trip.getPumpUsed();

            if (pumpUsed == PumpUsed.BOTH) {
                motorController.stopPumpInternal("inside");
                motorController.stopPumpInternal("outside");
            } else if (pumpUsed == PumpUsed.INSIDE) {
                motorController.stopPumpInternal("inside");
            } else if (pumpUsed == PumpUsed.OUTSIDE) {
                motorController.stopPumpInternal("outside");
            }

            // Publish event for trip completion (breaks circular dependency)
            eventPublisher.publishEvent(new TripAutoStopEvent(this, trip));

            logger.info("Successfully auto-stopped trip {}", trip.getId());

        } catch (Exception e) {
            logger.error("Auto-stop pump operation failed for trip {}",
                    trip.getId(), e);
            throw e;
        }
    }

    /**
     * Mark auto-stop as failed to prevent retry loops
     */
    private void markAutoStopFailed(CustomerTripLedger trip) {
        trip.setAutoStopAttemptedAt(LocalDateTime.now());
        tripRepo.save(trip);
    }

    /**
     * On system restart: Re-check active trips and schedule auto-stop
     * Uses event-driven approach instead of continuous polling
     */
    @PostConstruct
    public void recoverActiveTrips() {
        List<CustomerTripLedger> activeTrips = tripRepo.findByStatusAndAutoStopScheduled(
                TripStatus.FILLING, true);

        if (activeTrips.isEmpty()) {
            logger.info("No active trips found on startup");
            return;
        }
        
        logger.info("System startup: Found {} active trips to monitor, rescheduling auto-stop checks",
                activeTrips.size());
        
        for (CustomerTripLedger trip : activeTrips) {
            if (trip.getStartTime() == null || trip.getExpectedDurationSec() == null) {
                logger.warn("Trip {} missing startTime or expectedDuration, skipping recovery", trip.getId());
                continue;
            }

            // Calculate check time (5 seconds before expected completion)
            LocalDateTime checkTime = trip.getStartTime()
                    .plusSeconds(trip.getExpectedDurationSec() - CHECK_BEFORE_COMPLETION_SECONDS);

            LocalDateTime now = LocalDateTime.now();

            // Only schedule if check time hasn't passed
            if (checkTime.isAfter(now)) {
                scheduleAutoStopCheck(trip.getId(), checkTime);
                logger.debug("Rescheduled auto-stop check for trip {} at {}", trip.getId(), checkTime);
            } else {
                // Check immediately if already past check time
                logger.info("Trip {} check time has passed, checking immediately", trip.getId());
                checkAndStopTrip(trip.getId());
            }
        }
    }
}
