package com.mangle.retailshopapp.credit.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mangle.retailshopapp.credit.model.RcCreditReq;
import com.mangle.retailshopapp.credit.model.RcCreditReqDTO;

public interface RcCreditReqRepository extends JpaRepository<RcCreditReq, Integer> {

    @Query("SELECT new com.mangle.retailshopapp.credit.model.RcCreditReqDTO(r.credDttm, "
            + "CASE WHEN r.reqType = 'DBT' THEN r.reqAmt * -1 ELSE 0 END, "
            + "CASE WHEN r.reqType = 'DPAM' THEN r.reqAmt ELSE 0 END, "
            + "SUM(r.reqAmt) OVER (ORDER BY r.credDttm) AS Balance) " + "FROM RcCreditReq r "
            + "WHERE r.custId = :custId " + "ORDER BY r.credDttm DESC")
    List<RcCreditReqDTO> findTransactionHistory(@Param("custId") int custId);

  
}
