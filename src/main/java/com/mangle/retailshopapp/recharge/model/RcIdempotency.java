package com.mangle.retailshopapp.recharge.model;

import java.time.LocalDateTime;

import org.springframework.data.domain.Persistable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;

/**
 * A used {@code Idempotency-Key} (V28, design-api.md §7.1).
 *
 * <p>Required on the settle endpoint ONLY — the single unrecoverable operation.
 * §7's threat table puts it plainly: "duplicate money taken from a customer is
 * the one error the shop cannot detect after the fact."
 *
 * <p>Not needed on the others, each for its own reason: marking a recharge
 * complete is naturally idempotent (a repeat of the current status is a 200
 * no-op), a retry is guarded by a unique key on {@code FAILED_RECHARGE_ID}, and a
 * credit settlement is small, visible and correctable by a second entry in the
 * statement.
 *
 * <p><b>⚠ IMPLEMENTS {@code Persistable} BECAUSE THE ID IS ASSIGNED, AND WITHOUT
 * IT THE GUARD DOES NOT WORK AT ALL.</b> Found by executing rather than reading,
 * and it is the worst kind of bug this slice could have had.
 *
 * <p>{@code SimpleJpaRepository.save} decides between {@code persist} and
 * {@code merge} by asking whether the entity is new, and its default answer is
 * "the id is null". This id is a client-supplied UUID and so is never null, so
 * every {@code save} took the {@code merge} path — which issues a {@code SELECT}
 * and then an {@code UPDATE} when the row already exists. A second settle with
 * the same {@code Idempotency-Key} therefore updated the existing key row and
 * carried on to create a SECOND PAYMENT, silently taking the customer's money
 * twice. That is precisely the failure design-api.md §7 calls "the one error the
 * shop cannot detect after the fact", and the unique index could never fire
 * because no {@code INSERT} was ever attempted.
 *
 * <p>{@code isNew()} is backed by a transient flag that starts true and is
 * cleared by {@code @PostPersist} and {@code @PostLoad}, which is the documented
 * Spring Data pattern for assigned identifiers. A plain {@code return true} would
 * also force the insert, but would break the legitimate update at §4.3 step 4,
 * where {@code PAYMENT_ID} is filled in once the header has its id.
 */
@Getter
@Setter
@Entity
@Table(name = "rc_idempotency")
public class RcIdempotency implements Persistable<String> {

    @Id
    @Column(name = "KEY_VALUE", nullable = false, length = 80)
    private String keyValue;

    @Column(name = "ENDPOINT", nullable = false, length = 60)
    private String endpoint;

    /**
     * Nullable because §4.3 step 3 inserts this row BEFORE the payment header
     * exists, and that ordering is the entire mechanism: a duplicate key aborts
     * the transaction before any money is written. Set within the same
     * transaction once the header has its id.
     */
    @Column(name = "PAYMENT_ID")
    private Integer paymentId;

    @Column(name = "CRE_BY")
    private Integer creBy;

    @Column(name = "CRE_DTTM", nullable = false)
    private LocalDateTime creDttm;

    @Transient
    private boolean newRow = true;

    @Override
    public String getId() {
        return keyValue;
    }

    @Override
    public boolean isNew() {
        return newRow;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.newRow = false;
    }
}
