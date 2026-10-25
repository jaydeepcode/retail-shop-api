package com.mangle.retailshopapp.ticket.exception;

import java.util.Map;

import com.mangle.retailshopapp.recharge.exception.ErrorCode;
import com.mangle.retailshopapp.recharge.exception.RechargeException;

/** 404 — no booking with that id. */
public class BookingNotFoundException extends RechargeException {

    public BookingNotFoundException(long bookingId) {
        super(ErrorCode.BOOKING_NOT_FOUND, 404, "No booking with id " + bookingId + ".",
                Map.of("bookingId", bookingId));
    }
}
