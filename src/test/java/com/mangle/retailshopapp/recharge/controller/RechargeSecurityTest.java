package com.mangle.retailshopapp.recharge.controller;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Step 0 of the slice, asserted rather than assumed (design-api.md §3.5, log §15.11).
 *
 * <p>next-session-s3.md puts the stake plainly: without the matcher, "a water
 * customer's {@code ROLE_CUSTOMER} token reaches the entire counter API including
 * the credit book." That is a one-line change whose correctness is entirely
 * invisible in the diff — a matcher placed BELOW {@code anyRequest()} compiles,
 * deploys and silently does nothing, because Spring evaluates them in order.
 *
 * <p>So this asserts the three cases that matter: no token is 401, a customer's
 * token is 403, and a staff token gets through. Nothing else in the suite can tell
 * the difference between a working matcher and an absent one.
 *
 * <p>Paths carry no {@code /api} prefix: {@code server.servlet.context-path}
 * applies to the running server and MockMvc does not apply it, so a prefixed path
 * would match no security rule and fall through — the defect
 * {@code AuthenticationIntegrationTest}'s header already records.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("/recharge/** is staff-only")
class RechargeSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("no token is refused — as 403, which is NOT what §3.2 specifies")
    void anonymousIsRefused() throws Exception {
        // ⚠ A DIVERGENCE FROM THE SPEC, ASSERTED AS-IS AND FLAGGED RATHER THAN
        // FIXED. design-api.md §3.2 says "401 (no/expired token) and 403 (wrong
        // role) … apply to all rows". This application returns 403 for BOTH,
        // because SecurityConfig configures no AuthenticationEntryPoint: Spring's
        // ExceptionTranslationFilter turns an AccessDeniedException on an
        // anonymous authentication into 403 unless something is registered to
        // send 401 instead.
        //
        // It is refused either way, so nothing is open. What it costs is the
        // client's ability to tell "your session expired, log in again" from "you
        // are not allowed here" — which on a counter matters, because the first is
        // routine and the second is not.
        //
        // NOT changed here, because adding an entry point to SecurityConfig would
        // change the response of EVERY existing endpoint, including the ones the
        // water mobile app calls, and AuthenticationIntegrationTest shows 403 is
        // the established behaviour it was written against. That is an S0b-shaped
        // change needing the owner, not a side effect of a recharge slice.
        //
        // This test pins the CURRENT behaviour so the day someone does add the
        // entry point, this is what tells them the contract moved.
        mockMvc.perform(get("/recharge/config"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "watercustomer", authorities = {"ROLE_CUSTOMER"})
    @DisplayName("⚠ a water customer's token does NOT reach the counter")
    void customerIsRefused() throws Exception {
        // The whole reason the matcher exists. ROLE_CUSTOMER is held by the two
        // water customers who start pumps from the mobile app, and
        // CustomerRegistrationService.createCustomerUser assigns exactly that and
        // nothing else — so this is the role the PUBLIC /customer/register path
        // can mint.
        mockMvc.perform(get("/recharge/config"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "watercustomer", authorities = {"ROLE_CUSTOMER"})
    @DisplayName("⚠ and it does not reach the write path either")
    void customerCannotSettle() throws Exception {
        // Checked separately from the read, because a matcher that happened to
        // cover only GET would pass the test above and still leave the shop's
        // money writable by a customer token.
        mockMvc.perform(post("/recharge/transactions")
                        .header("Idempotency-Key", "11111111-1111-1111-1111-111111111111")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recharges\":[],\"settlementLines\":[]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("ROLE_USER is the staff role and gets through")
    void staffIsAdmitted() throws Exception {
        // log §15.11: ROLE_USER already IS the staff role, so no ROLE_STAFF is
        // created. Two of the shop's ten users hold it.
        mockMvc.perform(get("/recharge/config"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "owner", authorities = {"ROLE_ADMIN", "ROLE_USER"})
    @DisplayName("the owner gets through")
    void adminIsAdmitted() throws Exception {
        mockMvc.perform(get("/recharge/config"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("a malformed basket is a 400 with field errors, not a 500")
    void validationIsA400() throws Exception {
        // §8.4's contrast: PurchasePartyController:110-113 returns a duplicate
        // registration as HTTP 500. An empty basket is a client mistake and must
        // read as one.
        mockMvc.perform(post("/recharge/transactions")
                        .header("Idempotency-Key", "22222222-2222-2222-2222-222222222222")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recharges\":[],\"settlementLines\":[]}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * ⚠ EVERY ONE OF THESE WAS A 500 before review finding 6.
     *
     * Spring raises {@code MethodArgumentTypeMismatchException} during argument
     * resolution, and only the {@code Exception} catch-all matched it — so a
     * mistyped filter returned "An unexpected error occurred." and logged a stack
     * trace at ERROR. The module handled the same failure in the request BODY and
     * not in the query string or the path.
     *
     * Asserted over HTTP rather than at the service layer, because argument
     * resolution happens before any service is reached: a unit test cannot see
     * this at all, which is why it survived.
     */
    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("⚠ a mistyped enum filter is a 400 naming the accepted values, not a 500")
    void badEnumParamIsA400() throws Exception {
        mockMvc.perform(get("/recharge/history").param("status", "BOGUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("status"))
                // The accepted values are part of the published contract (§6.1:
                // "enums cross the wire as their string names"), so naming them
                // saves a trip through the documentation and leaks nothing.
                .andExpect(jsonPath("$.fieldErrors[0].message")
                        .value(org.hamcrest.Matchers.containsString("COMPLETED")));
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("a non-numeric page is a 400")
    void badNumberParamIsA400() throws Exception {
        mockMvc.perform(get("/recharge/history").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].message")
                        .value(org.hamcrest.Matchers.containsString("whole number")));
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("an impossible date is a 400 showing the form expected")
    void badDateParamIsA400() throws Exception {
        mockMvc.perform(get("/recharge/day-summary").param("date", "2026-13-45"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("date"))
                .andExpect(jsonPath("$.fieldErrors[0].message")
                        .value(org.hamcrest.Matchers.containsString("2026-10-14")));
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("a bad enum in the PATH is a 400 too, not only in the query")
    void badEnumPathVariableIsA400() throws Exception {
        mockMvc.perform(get("/recharge/number-hints/MOBILEX/9000000002"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].message")
                        .value(org.hamcrest.Matchers.containsString("MOBILE")));
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("⚠ and the error body never carries Spring's own text")
    void errorBodyDoesNotEchoInternals() throws Exception {
        // §8.3: the message is replaced, never forwarded. Spring's own text names
        // Java classes and package paths.
        String body = mockMvc.perform(get("/recharge/history").param("status", "BOGUS"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("com.mangle"), body);
        assertFalse(body.contains("java.lang"), body);
        assertFalse(body.toLowerCase().contains("exception"), body);
        // And a traceId is always present, which is what replaces leaking the text.
        assertTrue(body.contains("\"traceId\""), body);
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("a missing Idempotency-Key is a 400, not a silent settle")
    void idempotencyKeyIsRequired() throws Exception {
        // §7.1 requires it on this endpoint only, because this is the one
        // unrecoverable operation. Omitting it must refuse rather than default.
        mockMvc.perform(post("/recharge/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recharges\":[],\"settlementLines\":[]}"))
                .andExpect(status().isBadRequest());
    }
}
