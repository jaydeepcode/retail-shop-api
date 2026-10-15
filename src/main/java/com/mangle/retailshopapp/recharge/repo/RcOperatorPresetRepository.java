package com.mangle.retailshopapp.recharge.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.mangle.retailshopapp.recharge.model.RcOperatorPreset;
import com.mangle.retailshopapp.recharge.model.RcOperatorPresetId;

/**
 * Amount chips per operator.
 *
 * <p>An EMPTY list is a valid answer, not missing data: log §14.10 gives TV no
 * presets at all, and gives {@code VI} none either. The screen must render a bare
 * amount field (or, for TV, a ±₹10 stepper) rather than wait for rows.
 */
@Repository
public interface RcOperatorPresetRepository
        extends JpaRepository<RcOperatorPreset, RcOperatorPresetId> {

    @Query("""
            select p from RcOperatorPreset p
             where p.id.operatorCode = :operatorCode and p.active = true
             order by p.sortOrder asc
            """)
    List<RcOperatorPreset> findActiveFor(@Param("operatorCode") String operatorCode);

    @Query("select p from RcOperatorPreset p where p.active = true order by p.sortOrder asc")
    List<RcOperatorPreset> findAllActive();
}
