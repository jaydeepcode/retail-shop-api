package com.mangle.retailshopapp.recharge.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A global scalar (V9, design-domain.md §4.7; requirement 10).
 *
 * <p>Config in tables with no screen, by decision. Eight rows after V28:
 * {@code credit.warning.days}, {@code eod.check.mode}, {@code fy.start.month},
 * {@code bank.default.account}, {@code float.imps.fee},
 * {@code change.display.threshold}, {@code surcharge.default.tv} and
 * {@code surcharge.default.mobile}.
 *
 * <p>⚠ ONE KEY HAS NO COMPILED-IN FALLBACK ON PURPOSE:
 * {@code bank.default.account} (log §9.3). A missing row there is a configuration
 * error that must fail loudly rather than quietly post to a guessed account.
 *
 * <p>And one key's ABSENCE is itself meaningful: {@code ledger.opening.date} is
 * written by the opening-balance call, and its absence is how the system knows
 * the ledger has not been opened yet (log §12). No migration may seed it.
 */
@Getter
@Setter
@Entity
@Table(name = "rc_setting")
public class RcSetting {

    @Id
    @Column(name = "SETTING_KEY", nullable = false, length = 60)
    private String settingKey;

    @Column(name = "SETTING_VALUE", nullable = false, length = 255)
    private String settingValue;

    @Column(name = "VALUE_TYPE", nullable = false, length = 10)
    private String valueType = "STRING";

    @Column(name = "DESCRIPTION", length = 255)
    private String description;

    @Column(name = "UPDATED_AT", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    @Column(name = "UPDATED_BY")
    private Integer updatedBy;
}
