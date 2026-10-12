package com.mangle.retailshopapp.accounting.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.mangle.retailshopapp.accounting.model.AccVoucherLine;

@Repository
public interface AccVoucherLineRepository extends JpaRepository<AccVoucherLine, Long> {

    List<AccVoucherLine> findByVoucherIdOrderByLineNo(Long voucherId);
}
