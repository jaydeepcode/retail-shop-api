package com.mangle.retailshopapp.water.event;

import com.mangle.retailshopapp.customer.model.CustomerTripLedger;
import org.springframework.context.ApplicationEvent;

/**
 * Event published when a trip should be auto-stopped.
 * This breaks the circular dependency between PumpAutoStopService and WaterTransactionService.
 */
public class TripAutoStopEvent extends ApplicationEvent {
    
    private final CustomerTripLedger trip;
    
    public TripAutoStopEvent(Object source, CustomerTripLedger trip) {
        super(source);
        this.trip = trip;
    }
    
    public CustomerTripLedger getTrip() {
        return trip;
    }
}
