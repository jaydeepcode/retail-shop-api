package com.mangle.retailshopapp.recharge.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.mangle.retailshopapp.recharge.model.RefRechargeRoute;

@Repository
public interface RefRechargeRouteRepository extends JpaRepository<RefRechargeRoute, String> {

    List<RefRechargeRoute> findByActiveTrueOrderBySortOrderAsc();
}
