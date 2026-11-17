package com.mangle.retailshopapp.accounting.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.mangle.retailshopapp.accounting.model.AccFloatMovement;

/**
 * The append-only float movement log (V24, design-ledger.md §3.2).
 *
 * <p>⚠ INSERT AND READ ONLY, AND NEVER AN UPDATE.
 * {@code trg_acc_float_movement_bu} raises {@code SQLSTATE 45000} on every
 * {@code UPDATE}, because the running weighted average is a {@code SUM} over
 * these rows and a mutable row would restate the cost basis of every sale after
 * it. {@code JpaRepository.save} on a row with an id IS an update, so nothing
 * here may re-save one.
 *
 * <p>The face-side total is read through
 * {@code AccBalanceRepository.faceLedgerOf}, which is a native
 * {@code SUM(FACE_DELTA)} — kept there because that is where the close reads it.
 */
@Repository
public interface AccFloatMovementRepository extends JpaRepository<AccFloatMovement, Long> {
}
