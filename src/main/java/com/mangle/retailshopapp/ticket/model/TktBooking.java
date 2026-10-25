package com.mangle.retailshopapp.ticket.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.mangle.retailshopapp.ticket.domain.BookingMode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One ticket booking (V31, design-ticket-booking.md §2).
 *
 * <p>{@code @Getter @Setter} rather than {@code @Data}, matching
 * {@code RcRecharge} and {@code AccVoucher}: a generated {@code toString} across
 * a reference is how a bidirectional entity graph produces a
 * {@code StackOverflowError} on its first log line.
 *
 * <p><b>⚠ The flow is READ from this row, never stored.</b>
 * {@code fareAmount == null} means flow 2 — the customer paid the platform
 * themselves and the shop never touched the fare, so there is no funding account
 * to name. {@code ck_tkt_funding} makes the two fields agree by construction:
 * {@code (FARE_AMOUNT IS NULL) = (FUNDING_ACCOUNT_CODE IS NULL)}. That is what
 * lets {@link #isShopFunded()} be a reading rather than a second opinion, and it
 * is why the screen offers one control instead of a flow toggle that could
 * contradict the amounts (T2, §7).
 */
@Getter
@Setter
@Entity
@Table(name = "tkt_booking")
public class TktBooking {

    /**
     * ⚠ {@code Long}, and here that IS what the schema says: {@code V31} declares
     * {@code BOOKING_ID BIGINT}.
     *
     * <p>Worth stating because the sibling ids went the other way three days
     * earlier. {@code rc_recharge.RECHARGE_ID} is {@code INT} and its field was
     * {@code Long} until 2026-10-22 — harmless at runtime, because JDBC widens
     * {@code int} to {@code long}, and invisible in production because
     * {@code ddl-auto=validate} runs in the {@code local} profile alone. The rule
     * the fix settled on is that the entity says what the schema says, in both
     * directions: {@code INT} is {@code Integer}, {@code BIGINT} is {@code Long}.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "BOOKING_ID")
    private Long bookingId;

    @Column(name = "BOOKING_DTTM", nullable = false)
    private LocalDateTime bookingDttm;

    /**
     * ⚠ {@code EnumType.STRING}. {@code ORDINAL} would store 0 and 1, and
     * {@code fk_tkt_mode} references {@code ref_booking_mode.CODE} — so an
     * ordinal would fail the foreign key on every insert, and reordering the enum
     * would silently rewrite history if it did not.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "BOOKING_MODE", nullable = false, length = 10)
    private BookingMode bookingMode;

    /**
     * Stored, never inferred from commission ÷ 60 (T6).
     *
     * <p>{@code SMALLINT} in the schema, so {@code Short} here by the same rule
     * the id follows. FY2026's two bad rows — ₹3,435 and ₹2,160 of commission on
     * single baskets — would imply 57.25 and 36 passengers; the defect they show
     * is an implausible count, not an indivisible amount.
     */
    @Column(name = "PASSENGER_COUNT", nullable = false)
    private Short passengerCount;

    /**
     * {@code NULL} ⇔ flow 2. Not zero: a free ticket and a ticket the shop never
     * paid for are different events, and {@code ck_tkt_fare} refuses a zero fare
     * for exactly that reason.
     */
    @Column(name = "FARE_AMOUNT", precision = 38, scale = 2)
    private BigDecimal fareAmount;

    /** The shop's margin. ₹60 × passengers is today's price, not a law (§5). */
    @Column(name = "COMMISSION_AMOUNT", nullable = false, precision = 38, scale = 2)
    private BigDecimal commissionAmount;

    /**
     * Which of the four bank accounts paid the platform — {@code BANK_JANATA},
     * {@code BANK_SELF}, {@code BANK_WIFE} or {@code BANK_FATHER} (log §10.6).
     * Required exactly when there is a fare, per {@code ck_tkt_funding}.
     */
    @Column(name = "FUNDING_ACCOUNT_CODE", length = 30)
    private String fundingAccountCode;

    /** Required only for a credit tender (FRD §8.7), not for the booking itself. */
    @Column(name = "CUST_ID")
    private Integer custId;

    /** Free text: train or bus, route, a PNR if the staff want one (§2). */
    @Column(name = "NOTE", length = 255)
    private String note;

    @Column(name = "CRE_BY")
    private Integer creBy;

    @Column(name = "CRE_DTTM", insertable = false, updatable = false)
    private LocalDateTime creDttm;

    @Column(name = "UPD_DTTM", insertable = false, updatable = false)
    private LocalDateTime updDttm;

    /**
     * Flow 1: the shop paid the platform and is owed the fare back by the
     * customer.
     *
     * <p>⚠ Derived, never a column. A stored flag is a second thing to keep in
     * step with the amounts, and {@code ck_tkt_funding} already guarantees these
     * two fields cannot disagree — so this reading cannot be wrong unless the
     * constraint is missing, which V32 asserts and
     * {@code TicketBookingMigrationTest} probes.
     */
    public boolean isShopFunded() {
        return fareAmount != null;
    }

    /** What the customer pays: the fare if the shop funded it, plus the margin. */
    public BigDecimal totalDue() {
        return isShopFunded() ? fareAmount.add(commissionAmount) : commissionAmount;
    }
}
