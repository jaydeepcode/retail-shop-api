package com.mangle.retailshopapp.recharge.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.mangle.retailshopapp.recharge.domain.ServiceType;
import com.mangle.retailshopapp.recharge.model.RcOperator;

/**
 * The operator catalogue.
 *
 * <p>Nine active operators after the Vi merge. The ordering follows
 * {@code idx_operator_kind_active (RECHARGE_KIND, IS_ACTIVE, SORT_ORDER)}, which
 * §7.2 chose as "a covering path for the whole query rather than a scan of
 * thirteen rows that grows with the catalog".
 */
@Repository
public interface RcOperatorRepository extends JpaRepository<RcOperator, String> {

    List<RcOperator> findByActiveTrueOrderByRechargeKindAscSortOrderAsc();

    List<RcOperator> findByRechargeKindAndActiveTrueOrderBySortOrderAsc(ServiceType rechargeKind);
}
