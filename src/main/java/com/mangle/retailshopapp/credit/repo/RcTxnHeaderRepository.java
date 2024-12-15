package com.mangle.retailshopapp.credit.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.mangle.retailshopapp.credit.model.RcTxnHeader;

@Repository
public interface RcTxnHeaderRepository extends JpaRepository<RcTxnHeader, Integer> {
}

