package com.mangle.retailshopapp.water.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mangle.retailshopapp.water.model.TripFlowRateAnomaly;

public interface TripAnomalyRepository extends JpaRepository<TripFlowRateAnomaly, Integer> {
}

