package com.mangle.retailshopapp.accounting.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /vouchers/**} and {@code /config} are staff-only — asserted rather than
 * assumed (design-ledger-api.md §3).
 *
 * <p>§3 requires these paths to be "explicitly role-gated in the same release
 * that introduces them, not after", and gives the reason: {@code SecurityConfig}
 * ends in {@code anyRequest().authenticated()} and <b>no {@code ROLE_STAFF}
 * exists</b>, so a water {@code ROLE_CUSTOMER} token would otherwise reach
 * endpoints that move money between accounts. {@code /accounts/**} was gated with
 * the close; {@code /vouchers/**} is gated here.
 *
 * <p>⚠ <b>A MATCHER'S CORRECTNESS IS INVISIBLE IN THE DIFF.</b> Spring evaluates
 * them in order, so one placed below {@code anyRequest()} compiles, deploys and
 * silently does nothing — and {@code /config} found the sharper version of that:
 * it sits below a {@code permitAll} for {@code "/*"}, which matches any
 * single-segment path, so the endpoint was <b>fully public</b> until the matcher
 * was moved above it. {@code ConfigControllerTest} is where that one is recorded.
 *
 * <p>403 and not 401 for an anonymous request, which is this application's
 * established behaviour rather than what §3.2 specifies — the divergence is
 * explained at length in {@code RechargeSecurityTest} and is not this slice's to
 * change, since an {@code AuthenticationEntryPoint} would alter the response of
 * every existing endpoint. Refused is refused; what matters here is <b>not
 * 2xx</b>.
 *
 * <p>Paths carry no {@code /api} prefix: {@code server.servlet.context-path}
 * applies to the running server and MockMvc does not apply it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("/vouchers/** and /config are staff-only")
class LedgerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String A_PAYMENT = """
            {"voucherDate":"2026-11-16","expenseAccountCode":"EXP_ELEC_WATER",
             "sourceAccountCode":"CASH_SAFE","amount":1200.00,"narration":"MSEB bill"}
            """;

    @Test
    @DisplayName("no token cannot post a voucher")
    void anonymousCannotPostAVoucher() throws Exception {
        mockMvc.perform(post("/vouchers/payments")
                        .header("Idempotency-Key", "k-anon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(A_PAYMENT))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "water-customer", authorities = {"ROLE_CUSTOMER"})
    @DisplayName("⚠ a water customer's token cannot post a voucher")
    void customerCannotPostAVoucher() throws Exception {
        // The case §3 is actually about. ROLE_CUSTOMER is minted by the PUBLIC
        // /customer/register path (CustomerRegistrationService assigns
        // List.of("ROLE_CUSTOMER") and nothing else), so without the matcher
        // anyone who can register a water account could pay the shop's money out
        // of its safe.
        mockMvc.perform(post("/vouchers/payments")
                        .header("Idempotency-Key", "k-cust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(A_PAYMENT))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "water-customer", authorities = {"ROLE_CUSTOMER"})
    @DisplayName("a water customer's token cannot read the chart of accounts either")
    void customerCannotReadTheAccounts() throws Exception {
        mockMvc.perform(get("/accounts")).andExpect(status().isForbidden());
        mockMvc.perform(get("/accounts/upi-clearing/pending"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/config")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("staff get through — ROLE_USER already IS the staff role")
    void staffGetThrough() throws Exception {
        // Measured on the restore 2026-10-12: the only values in rc_user.roles
        // are ROLE_ADMIN|ROLE_USER (the owner), ROLE_USER (working staff) and
        // ROLE_CUSTOMER (water customers). So ROLE_STAFF is never created, and
        // these two authorities are the recharge counter's, not a new role.
        mockMvc.perform(get("/accounts")).andExpect(status().isOk());
        mockMvc.perform(get("/config")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "owner", authorities = {"ROLE_ADMIN", "ROLE_USER"})
    @DisplayName("the owner gets through")
    void ownerGetsThrough() throws Exception {
        mockMvc.perform(get("/accounts")).andExpect(status().isOk());
    }
}
