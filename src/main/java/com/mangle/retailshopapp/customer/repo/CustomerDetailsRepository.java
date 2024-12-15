package com.mangle.retailshopapp.customer.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mangle.retailshopapp.customer.model.CustomerDetails;

public interface CustomerDetailsRepository extends JpaRepository<CustomerDetails, Long> {

   @Query("select c from CustomerDetails c where c.customerName like %:customerName% and c.custId in (select w.customerId from WaterPurchaseParty w)")
   List<CustomerDetails> findCustomersByName(@Param("customerName") String customerName);

   @Query("select c from CustomerDetails c where c.customerName like %:bankName%")
   CustomerDetails findBankByName(@Param("bankName") String bankName);

   boolean existsByContactNum(String contactNum);
}
