package com.mangle.retailshopapp.accounting.repo;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.mangle.retailshopapp.accounting.model.AccVoucher;

@Repository
public interface AccVoucherRepository extends JpaRepository<AccVoucher, Long> {

    /**
     * The idempotency lookup (§5.4). Backed by {@code uk_vch_idem}, so this is a
     * unique index probe rather than a scan.
     */
    Optional<AccVoucher> findByIdempotencyKey(String idempotencyKey);
}
