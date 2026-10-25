package com.mangle.retailshopapp.ticket.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mangle.retailshopapp.ticket.model.TktBooking;

/**
 * ⚠ {@code Long}, matching {@code BOOKING_ID BIGINT} and the entity's field. The
 * generic and the field have to agree: S3 found four repositories whose generic
 * said {@code Long} over an {@code INT} column, which compiled and ran and was
 * only caught when {@code ddl-auto=validate} was finally exercised.
 */
public interface TktBookingRepository extends JpaRepository<TktBooking, Long> {
}
