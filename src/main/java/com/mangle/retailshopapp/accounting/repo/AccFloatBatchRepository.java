package com.mangle.retailshopapp.accounting.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.mangle.retailshopapp.accounting.model.AccFloatBatch;

/**
 * One row per float top-up (V24, design-ledger.md §3.2).
 *
 * <p>Insert-only from endpoint 19. There is no finder yet because nothing reads a
 * batch: the weighted average is a {@code SUM} over {@code acc_float_movement}
 * (§3.4) and the GL carries the cost basis, so a batch is evidence of a purchase
 * rather than an input to a calculation — until a bonus-on-topup wallet exists
 * and §3.3's per-batch arithmetic wakes up.
 */
@Repository
public interface AccFloatBatchRepository extends JpaRepository<AccFloatBatch, Long> {
}
