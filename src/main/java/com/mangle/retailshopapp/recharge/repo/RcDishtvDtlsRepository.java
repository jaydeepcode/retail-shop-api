package com.mangle.retailshopapp.recharge.repo;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.mangle.retailshopapp.recharge.model.RcDishtvDtls;

/**
 * TV connection lookup. 458 rows.
 *
 * <p>No search index and none needed: §12's over-engineering register says so
 * explicitly for "a 458-row TV table and a 506-row customer table". A contains
 * match over 458 rows is faster than the network round trip that carries it.
 */
@Repository
public interface RcDishtvDtlsRepository extends JpaRepository<RcDishtvDtls, String> {

    @Query("""
            select d from RcDishtvDtls d
             where d.dishtvNo like concat(:query, '%')
                or d.contactNum like concat('%', :query, '%')
                or lower(d.name) like concat('%', lower(:query), '%')
             order by d.name asc
            """)
    List<RcDishtvDtls> search(@Param("query") String query, Pageable pageable);
}
