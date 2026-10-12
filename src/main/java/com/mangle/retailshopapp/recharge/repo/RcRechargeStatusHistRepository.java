package com.mangle.retailshopapp.recharge.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.mangle.retailshopapp.recharge.model.RcRechargeStatusHist;

/**
 * The status trail. Append-only by use rather than by constraint — nothing here
 * updates or deletes, and {@code RechargeFulfilmentService} is its only writer.
 */
@Repository
public interface RcRechargeStatusHistRepository extends JpaRepository<RcRechargeStatusHist, Long> {

    List<RcRechargeStatusHist> findByRechargeIdOrderByChangedAtAsc(Long rechargeId);
}
