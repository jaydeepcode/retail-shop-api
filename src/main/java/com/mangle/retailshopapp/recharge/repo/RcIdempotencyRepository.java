package com.mangle.retailshopapp.recharge.repo;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.mangle.retailshopapp.recharge.model.RcIdempotency;

@Repository
public interface RcIdempotencyRepository extends JpaRepository<RcIdempotency, String> {

    Optional<RcIdempotency> findByKeyValue(String keyValue);

    /**
     * The 48-hour prune (§7.1).
     *
     * <p>Not scheduled by anything in S3. The table grows by one row per sale —
     * about seven a day — so at 48 hours it holds a dozen rows and at a year of
     * neglect about 2,500. Worth having the query; not worth a scheduler and its
     * failure modes until something is actually accumulating.
     */
    @Modifying
    @Query("delete from RcIdempotency i where i.creDttm < :before")
    int pruneBefore(@Param("before") LocalDateTime before);
}
