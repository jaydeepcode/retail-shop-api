package com.mangle.retailshopapp.accounting.repo;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.mangle.retailshopapp.accounting.model.AccReconciliation;

@Repository
public interface AccReconciliationRepository extends JpaRepository<AccReconciliation, Long> {

    /**
     * The replay read, after {@code uk_recon_account_date} has refused a second
     * close.
     *
     * <p>⚠ Called from a FRESH read-only transaction, never from the aborted one.
     * A constraint violation marks the transaction rollback-only, so the rows
     * this is looking for are invisible from inside the transaction that tried to
     * duplicate them (design-ledger-api.md §1.3, RechargeController.java:240-247).
     */
    List<AccReconciliation> findByAccountCodeInAndReconDateOrderByAccountCode(
            Collection<String> accountCodes, LocalDate reconDate);

    Optional<AccReconciliation> findByAccountCodeAndReconDate(String accountCode,
                                                              LocalDate reconDate);

    /** Endpoint 3, unfiltered: one account's close history, newest first. */
    Page<AccReconciliation> findByAccountCodeOrderByReconDateDesc(String accountCode,
                                                                  Pageable pageable);

    /**
     * Every close on file for one account up to a date, oldest first — endpoint
     * 5's settled-per-collection-date figures.
     *
     * <p>⚠ For {@code UPI_CLEARING} a row's {@code RECON_DATE} is the COLLECTION
     * date and its {@code COUNTED_AMOUNT} is what the bank has credited against
     * that date so far, which is not quite what the column means for a cash
     * drawer: there {@code COUNTED_AMOUNT} is a one-time physical count. V22's
     * own header says this table "covers all three reconciliation points with one
     * table", and the UPI reading is the one where a row ACCUMULATES, because A4
     * allows a date to settle in instalments.
     */
    List<AccReconciliation> findByAccountCodeAndReconDateLessThanEqualOrderByReconDate(
            String accountCode, LocalDate asOf);

    /** Endpoint 3 with {@code ?from=&to=}, both inclusive. */
    Page<AccReconciliation> findByAccountCodeAndReconDateBetweenOrderByReconDateDesc(
            String accountCode, LocalDate fromInclusive, LocalDate toInclusive, Pageable pageable);

    /**
     * The newest close per account, one row each, for the preview's
     * {@code lastClosedAt} and {@code lastReconDate}.
     *
     * <p>⚠ BOTH COLUMNS COME OFF THE SAME ROW, which is the whole reason this is
     * a subquery rather than {@code MAX(RECON_DATE), MAX(CLOSED_AT)} in one
     * aggregate. Those two maxima belong to different closes whenever a day is
     * sealed out of order — which is exactly what closing a skipped day does —
     * and the pair reported would be one that never existed. §1.1 returns both
     * fields precisely because they answer different questions and diverge.
     *
     * <p>{@code uk_recon_account_date} makes {@code MAX(RECON_DATE)} resolve to
     * exactly one row per account, so no tie-break is needed.
     */
    @Query(value = "SELECT r.* FROM acc_reconciliation r "
            + " WHERE r.ACCOUNT_CODE IN (:codes) "
            + "   AND r.RECON_DATE = (SELECT MAX(r2.RECON_DATE) FROM acc_reconciliation r2 "
            + "                        WHERE r2.ACCOUNT_CODE = r.ACCOUNT_CODE)",
            nativeQuery = true)
    List<AccReconciliation> findNewestPerAccount(@Param("codes") Collection<String> codes);
}
