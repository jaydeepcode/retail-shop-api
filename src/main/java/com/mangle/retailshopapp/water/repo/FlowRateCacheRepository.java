package com.mangle.retailshopapp.water.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mangle.retailshopapp.water.model.FlowRateCache;
import com.mangle.retailshopapp.water.model.FlowRateCacheId;

public interface FlowRateCacheRepository extends JpaRepository<FlowRateCache, FlowRateCacheId> {
}

