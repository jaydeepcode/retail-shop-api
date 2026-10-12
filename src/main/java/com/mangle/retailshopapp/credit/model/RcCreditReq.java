package com.mangle.retailshopapp.credit.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The append-only credit tally (design-domain.md §4.5).
 *
 * <p>{@code DBT} rows are stored NEGATIVE and {@code DPAM} rows positive, so
 * {@code SUM(REQ_AMT)} is negative for someone who owes money.
 * {@code recharge.domain.Outstanding} is the one place that sign convention is
 * normalised; nothing else should do its own arithmetic on {@code reqAmt}.
 *
 * <p>Append-only is what makes the settle transaction safe: §4.3 step 7 is an
 * INSERT, never a read-modify-write of a stored balance, so a credit cannot be
 * lost or double-applied.
 *
 * <p>⚠ {@code @Data} IS LEFT IN PLACE HERE, deliberately, and it is worth saying
 * why rather than leaving the inconsistency unexplained.
 * implementation-plan.md §3 replaced {@code @Data} with {@code @Getter @Setter}
 * on {@code RcTxnHeader} and {@code RcTxnDetail} because those two hold a
 * BIDIRECTIONAL reference, where a generated {@code toString} recurses into a
 * guaranteed {@code StackOverflowError} on the first log of a transaction. This
 * entity has no such reference, so that specific hazard does not apply.
 *
 * <p>What does still apply is the milder objection: {@code @Data} generates
 * {@code equals}/{@code hashCode} over mutable fields including a generated id,
 * which makes an entity unsafe as a {@code Set} member or map key before it is
 * persisted. Nothing puts one in a {@code Set} today. Changing it would touch
 * {@code WaterTransactionService}'s write path, which design-domain.md R4 says
 * must not change before S6 — so it is recorded as a follow-up rather than done
 * inside a recharge slice.
 */
@Entity
@Data
@Table(name = "rc_credit_req")
public class RcCreditReq {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "CRE_REQ_ID")
    private int id;

    @Column(name = "CRED_DTTM", nullable = false)
    private LocalDateTime credDttm;

    @Column(name = "REQ_TYPE", nullable = false)
    private String reqType;

    @Column(name = "REQ_AMT", nullable = false)
    private BigDecimal reqAmt;

    @Column(name = "CUST_ID", nullable = false)
    private int custId;

    /**
     * Who extended the credit (V28, design-domain.md §4.5; requirement 7).
     *
     * <p>Nullable and NULL for all 4,301 historical rows — §8.8 found that no
     * record of who extended a legacy credit exists anywhere.
     *
     * <p>Together with {@link #note} and the timestamp, this IS the authorisation
     * control for credit. design-api.md §3.5 is explicit that requirement 9 means
     * no per-row authorisation at all — any staff member may extend credit — so
     * what makes it accountable is the record, taken from {@code Principal}.
     */
    @Column(name = "CRE_BY")
    private Integer creBy;

    @Column(name = "NOTE", length = 255)
    private String note;

    /**
     * The settlement this credit arose from (V28, §4.5).
     *
     * <p>Nullable, and back-fillable from the 2,844 {@code CRDT} rows — but that
     * is V13's job on cutover day, so every row reads NULL until then.
     */
    @Column(name = "TXN_ID")
    private Integer txnId;
}
