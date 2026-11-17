package com.mangle.retailshopapp.settings;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /config} — the shared config a shop-web session is waiting on.
 *
 * <p>Three things are under test and only one of them is the payload.
 *
 * <ul>
 *   <li><b>The figure is the setting's</b>, not a literal. Item 3 of
 *       next-session-s5.md is that ₹5 was written in three places because there
 *       was no endpoint to read it from; a test that asserted {@code 5.00}
 *       against a seeded {@code 5.00} would pass against a hardcoded one too, so
 *       {@link #thresholdComesFromTheSettingAndNotFromAConstant()} edits the row
 *       and expects the response to move.
 *   <li><b>{@code bank.default.account} is absent.</b> §1.2 resolves the bank
 *       server-side (log §9.3) and shipping it invites a client to send it back.
 *       An omission nothing asserts is an omission someone adds back.
 *   <li><b>The path is not public.</b> See
 *       {@link #unauthenticatedIsRefused()} — this one found a real hole.
 * </ul>
 *
 * <p>No rows are seeded on H2 ({@code spring.flyway.enabled=false}; the
 * migrations are MySQL-specific), so the defaults answer unless a test writes a
 * row. That makes the fallbacks testable here, which is the half
 * {@code LedgerMigrationTest} cannot reach.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("GET /config — the shop-wide settings")
class ConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SettingService settingService;

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("the four fields, and the two money figures as the owner confirmed them")
    void theFourFields() throws Exception {
        mockMvc.perform(get("/config").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                // V9:229 and V9:220 both seed 5.00; with no row on H2 these are
                // SettingService's fallbacks, which is the degraded path and the
                // one nothing else covers.
                .andExpect(jsonPath("$.changeDisplayThreshold").value(5.00))
                .andExpect(jsonPath("$.floatImpsFee").value(5.00))
                // 4 for the Indian financial year (design-domain.md §9.1).
                .andExpect(jsonPath("$.fyStartMonth").value(4))
                .andExpect(jsonPath("$.currentFinancialYear").value(
                        expectedFinancialYearLabel()));
    }

    /**
     * ⚠ THE ONE ASSERTION THAT CAN TELL A SETTING FROM A LITERAL.
     *
     * <p>Both are {@code 5.00} in production, so every other test here passes
     * against a hardcoded figure. This edits the row to a value nothing would
     * ever hardcode and expects the endpoint to answer it.
     */
    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("⚠ the threshold is READ, not compiled in")
    void thresholdComesFromTheSettingAndNotFromAConstant() throws Exception {
        saveSetting(SettingService.CHANGE_DISPLAY_THRESHOLD, "3.50");
        saveSetting(SettingService.FLOAT_IMPS_FEE, "7.25");
        try {
            mockMvc.perform(get("/config"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.changeDisplayThreshold").value(3.50))
                    .andExpect(jsonPath("$.floatImpsFee").value(7.25));

            // And the recharge counter answers the SAME figure from the same
            // row, which is the duplication item 3 removed: two accessors with
            // two fallbacks could answer differently to two screens.
            assertEquals(new BigDecimal("3.50"), settingService.changeDisplayThreshold());
        } finally {
            jdbc.update("DELETE FROM rc_setting");
        }
    }

    /**
     * ⚠ {@code bank.default.account} IS WITHHELD, AND A ROW EXISTING MAKES NO
     * DIFFERENCE. §1.2 resolves the bank server-side when a settlement posts
     * (log §9.3); a client that can read it is a client that can send it back.
     */
    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("⚠ bank.default.account is not on the response, even when it is set")
    void bankAccountIsNotShipped() throws Exception {
        saveSetting(SettingService.BANK_DEFAULT_ACCOUNT, "BANK_JANATA");
        try {
            mockMvc.perform(get("/config"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.bankDefaultAccount").doesNotExist())
                    // Belt and braces: not under any other name either.
                    .andExpect(jsonPath("$.*", org.hamcrest.Matchers.hasSize(4)))
                    .andExpect(content().string(
                            org.hamcrest.Matchers.not(
                                    org.hamcrest.Matchers.containsString("BANK_JANATA"))));
        } finally {
            jdbc.update("DELETE FROM rc_setting");
        }
    }

    /**
     * ⚠ <b>THIS FOUND A REAL HOLE AND WAS WATCHED TO FAIL.</b>
     *
     * <p>{@code SecurityConfig} permits {@code "/*"} for the SPA shell, and Ant's
     * {@code /*} matches exactly one path segment — so {@code /config} was inside
     * it. Matchers are evaluated in order, so a {@code /config} rule added
     * BELOW that line is never reached and the endpoint is readable with no token.
     * <b>Verified by moving the matcher back under {@code "/*"}: this test then
     * fails on "Status expected:&lt;403&gt; but was:&lt;200&gt;" and the body is
     * the config.</b>
     *
     * <p>403 rather than 401 because that is what this application answers to an
     * unauthenticated request — {@code JwtRequestFilter} sets no authentication
     * and the {@code AccessDeniedHandler} answers before any entry point does.
     * {@code AuthenticationIntegrationTest} records the same thing. The status is
     * not the point; <b>not 200</b> is.
     *
     * <p>Nothing on {@code /config} is secret. The point is §3's: the default
     * must be deny, and a water {@code ROLE_CUSTOMER} token reaching shop config
     * is the same class of mistake as it reaching the credit book.
     */
    @Test
    @DisplayName("⚠ no token is refused, because /* nearly made this public")
    void unauthenticatedIsRefused() throws Exception {
        mockMvc.perform(get("/config"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "water-customer", authorities = {"ROLE_CUSTOMER"})
    @DisplayName("a water customer's token is refused")
    void customerTokenIsRefused() throws Exception {
        mockMvc.perform(get("/config"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------

    /**
     * ⚠ {@code JdbcTemplate}, NOT THE REPOSITORY. {@code RcSetting.updatedAt} is
     * mapped {@code nullable = false, insertable = false} — MySQL fills it from
     * the column's {@code DEFAULT CURRENT_TIMESTAMP}, which Hibernate does not
     * reproduce when it builds the H2 schema from the entity, so a repository
     * {@code save} fails on "NULL not allowed for column UPDATED_AT". Worth
     * recording rather than working around silently: the entity is not wrong and
     * neither is the column, but the two only agree where a migration made the
     * table.
     */
    private void saveSetting(String key, String value) {
        jdbc.update("INSERT INTO rc_setting (SETTING_KEY, SETTING_VALUE, VALUE_TYPE, "
                        + "DESCRIPTION, UPDATED_AT) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                key, value,
                SettingService.BANK_DEFAULT_ACCOUNT.equals(key) ? "STRING" : "DECIMAL",
                "set by ConfigControllerTest");
    }

    /**
     * Derived the same way the endpoint derives it, from today rather than
     * pinned: a label pinned to 2026-27 starts failing on 1 April 2027, and a
     * test that has to be edited on a date is a test that gets deleted.
     */
    private static String expectedFinancialYearLabel() {
        LocalDate today = LocalDate.now();
        int startYear = today.getMonthValue() >= 4 ? today.getYear() : today.getYear() - 1;
        return "%d-%02d".formatted(startYear, (startYear + 1) % 100);
    }
}
