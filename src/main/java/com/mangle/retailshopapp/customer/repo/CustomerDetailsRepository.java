package com.mangle.retailshopapp.customer.repo;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mangle.retailshopapp.customer.model.CustomerDetails;

public interface CustomerDetailsRepository extends JpaRepository<CustomerDetails, Long> {

    Optional<CustomerDetails> findByUserId(Integer userId);
    
    Optional<CustomerDetails> findByContactNum(String contactNum);
    
    Optional<CustomerDetails> findByCustId(Integer custId);

    @Query("select c from CustomerDetails c where (CONCAT(c.firstName, ' ', c.lastName) like %:customerName% OR c.firstName like %:customerName% OR c.lastName like %:customerName%) and c.custId in (select w.customerId from WaterPurchaseParty w)")
    List<CustomerDetails> findCustomersByName(@Param("customerName") String customerName);

    @Query("select c from CustomerDetails c where (CONCAT(c.firstName, ' ', c.lastName) like %:bankName% OR c.firstName like %:bankName% OR c.lastName like %:bankName%)")
    CustomerDetails findBankByName(@Param("bankName") String bankName);

    boolean existsByContactNum(String contactNum);

    @Query(value = """
            WITH LastZeroBalance AS (
                SELECT
                    pd.cust_id,
                    MAX(pd.trip_date_time) AS last_zero_balance_date
                FROM
                    wt_purchase_details pd
                WHERE
                    pd.balance_amount = 0
                GROUP BY
                    pd.cust_id
            ),
            PendingTrips AS (
                SELECT
                    pd.cust_id,
                    pd.trip_date_time,
                    pd.balance_amount,
                    ROW_NUMBER() OVER (
                        PARTITION BY pd.cust_id
                        ORDER BY pd.trip_date_time DESC
                    ) AS pending_rank
                FROM
                    wt_purchase_details pd
                LEFT JOIN
                    LastZeroBalance lzb
                        ON pd.cust_id = lzb.cust_id
                WHERE
                    lzb.last_zero_balance_date IS NULL
                    OR pd.trip_date_time > lzb.last_zero_balance_date
            ),
            PendingTripCounts AS (
                SELECT
                    pd.cust_id,
                    COUNT(*) AS pending_trip_count
                FROM
                    wt_purchase_details pd
                LEFT JOIN
                    LastZeroBalance lzb
                        ON pd.cust_id = lzb.cust_id
                WHERE
                    lzb.last_zero_balance_date IS NULL
                    OR pd.trip_date_time > lzb.last_zero_balance_date
                GROUP BY
                    pd.cust_id
            ),
            LatestTrips AS (
                SELECT
                    pd.cust_id,
                    pd.trip_date_time,
                    ROW_NUMBER() OVER (
                        PARTITION BY pd.cust_id
                        ORDER BY pd.trip_date_time DESC
                    ) AS trip_rank
                FROM
                    wt_purchase_details pd
            )
            SELECT
                cd.cust_id,
                CONCAT(cd.FIRST_NAME, ' ', cd.LAST_NAME) AS cust_name,
                cd.contact_num,
                IFNULL(ptc.pending_trip_count, 0) AS TransactionCount,
                IFNULL(pt.balance_amount, 0) AS MaxBalanceAmount,
                COALESCE(lt.trip_date_time, lzb.last_zero_balance_date) AS Latest_Trip_Date
            FROM
                rs_cust_dtls cd
            INNER JOIN
                wt_purchase_party purchase_party
                    ON purchase_party.customer_id = cd.cust_id
            LEFT JOIN
                LastZeroBalance lzb
                    ON cd.cust_id = lzb.cust_id
            LEFT JOIN
                PendingTripCounts ptc
                    ON cd.cust_id = ptc.cust_id
            LEFT JOIN
                PendingTrips pt
                    ON cd.cust_id = pt.cust_id
                    AND pt.pending_rank = 1
            LEFT JOIN
                LatestTrips lt
                    ON cd.cust_id = lt.cust_id
                    AND lt.trip_rank = 1
            ORDER BY
                COALESCE(pt.trip_date_time, lt.trip_date_time, lzb.last_zero_balance_date) IS NULL,
                COALESCE(pt.trip_date_time, lt.trip_date_time, lzb.last_zero_balance_date) DESC,
                cd.cust_id DESC
            LIMIT
                6
                           """, nativeQuery = true)
    List<Map<String, Object>> findRecentCustomersWithPendingTrips();
}
