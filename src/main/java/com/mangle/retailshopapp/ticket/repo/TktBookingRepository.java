package com.mangle.retailshopapp.ticket.repo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.mangle.retailshopapp.ticket.model.TktBooking;

/**
 * ⚠ {@code Long}, matching {@code BOOKING_ID BIGINT} and the entity's field. The
 * generic and the field have to agree: S3 found four repositories whose generic
 * said {@code Long} over an {@code INT} column, which compiled and ran and was
 * only caught when {@code ddl-auto=validate} was finally exercised.
 */
public interface TktBookingRepository extends JpaRepository<TktBooking, Long> {

    /**
     * The replay lookup. Reached only from the duplicate-key catch, in a fresh
     * read-only transaction — uk_tkt_idem is what guarantees at most one row.
     */
    Optional<TktBooking> findByIdempotencyKey(String idempotencyKey);

    /**
     * A day's bookings, newest first.
     *
     * <p>Half-open on purpose: {@code [start of day, start of next day)} rather
     * than {@code BETWEEN date AND date 23:59:59}, which silently drops anything
     * in the last second of the day.
     */
    List<TktBooking> findByBookingDttmBetweenOrderByBookingDttmDesc(
            LocalDateTime fromInclusive, LocalDateTime toExclusive);

    Page<TktBooking> findByBookingDttmBetweenOrderByBookingDttmDesc(
            LocalDateTime fromInclusive, LocalDateTime toExclusive, Pageable pageable);
}
