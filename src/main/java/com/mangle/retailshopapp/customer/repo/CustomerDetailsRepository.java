package com.mangle.retailshopapp.customer.repo;

import java.util.List;
import java.util.Map;

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

   @Query(value = """
              WITH LastZeroBalance AS (
             SELECT
                 purchase_details.cust_id,
                 MAX(purchase_details.trip_date_time) AS LastZeroBalanceDate
             FROM
                 wt_purchase_details purchase_details
             WHERE
                 purchase_details.balance_amount = 0
             GROUP BY
                 purchase_details.cust_id
         )
         SELECT
             customer_details.cust_id,
             customer_details.cust_name,
             customer_details.contact_num,
             IFNULL(COUNT(purchase_details.id),0) AS TransactionCount,
             IFNULL(MAX(purchase_details.balance_amount),0) AS MaxBalanceAmount,
             IFNULL(MAX(purchase_details.trip_date_time),0) AS Latest_Trip_Date
         FROM
             rs_cust_dtls customer_details
         LEFT JOIN
             wt_purchase_details purchase_details
                 ON customer_details.cust_id = purchase_details.cust_id
         LEFT JOIN
             LastZeroBalance zero_balance
                 ON customer_details.cust_id = zero_balance.cust_id
         WHERE
             EXISTS (
                 SELECT 1
                 FROM wt_purchase_party purchase_party
                 WHERE purchase_party.customer_id = customer_details.cust_id
             )
             AND (
                 purchase_details.trip_date_time > zero_balance.LastZeroBalanceDate
                 OR zero_balance.LastZeroBalanceDate IS NULL
             )
         GROUP BY
             customer_details.cust_id,
             customer_details.cust_name,
             customer_details.contact_num

         UNION ALL

         SELECT
             customer_details.cust_id,
             customer_details.cust_name,
             customer_details.contact_num,
             0 AS TransactionCount,
             0 AS MaxBalanceAmount,
             zero_balance.LastZeroBalanceDate AS Latest_Trip_Date
         FROM
             rs_cust_dtls customer_details
         LEFT JOIN
             LastZeroBalance zero_balance
             ON customer_details.cust_id = zero_balance.cust_id
         WHERE
             EXISTS (
                 SELECT 1
                 FROM wt_purchase_party purchase_party
                 WHERE purchase_party.customer_id = customer_details.cust_id
             )
             AND NOT EXISTS (
                 SELECT 1
                 FROM wt_purchase_details purchase_details
                 WHERE purchase_details.cust_id = customer_details.cust_id
                 AND purchase_details.trip_date_time > zero_balance.LastZeroBalanceDate
             )
         GROUP BY
             customer_details.cust_id,
             customer_details.cust_name,
             customer_details.contact_num
         ORDER BY
             TransactionCount DESC, Latest_Trip_Date DESC
             LIMIT 6
               """, nativeQuery = true)
   List<Map<String, Object>> findRecentCustomersWithPendingTrips();
}
