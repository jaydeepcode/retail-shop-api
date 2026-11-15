package com.mangle.retailshopapp.accounting.controller;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.mangle.retailshopapp.accounting.LedgerCloseService;
import com.mangle.retailshopapp.accounting.LedgerPostingException;
import com.mangle.retailshopapp.accounting.model.response.PoolCloseResponse;
import com.mangle.retailshopapp.accounting.model.response.PoolCloseResponse.Sweep;
import com.mangle.retailshopapp.accounting.model.response.ReconciliationResponse;
import com.mangle.retailshopapp.apierror.ErrorCode;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ⚠ THE GUARD ON {@code RechargeExceptionHandler}'s {@code assignableTypes}, for
 * {@code AccountsController}.
 *
 * <p>That advice is scoped to named controllers and <b>this application has no
 * global advice</b> — making it global would change the error shape of every
 * existing endpoint, including the ones the water mobile app calls. So a
 * controller missing from that list gets Spring's default error body instead:
 * every 422 below would arrive as a bare 500 with no {@code code} on it.
 *
 * <p>This is the only kind of test that can see that. {@code LedgerCloseServiceTest}
 * passes either way, because the service throws the right exception either way —
 * the failure is entirely in whether anything is listening, and nothing about
 * adding a controller prompts you to go and amend the advice. S4 shipped exactly
 * this defect once already and found it by running the app, not by its 349 tests.
 *
 * <p><b>Verified by reverting the annotation, 2026-11-12: 7 of these 10 tests
 * fail.</b> Six error out with "Servlet Request processing failed:
 * LedgerPostingException" — MockMvc rethrows what nothing handled, which on a
 * real server is the bare 500 — and {@code reconDateIsRequired} fails on "No
 * value at JSON path $.code". The S4 equivalent was 7 of 8. This is recorded
 * because a guard test that has never been watched to fail is not a guard.


 * <p>The three that still pass are the two replay cases and the
 * no-{@code Idempotency-Key} case, and that is correct: they assert 409s and
 * 201s the controller produces itself, with no exception in the path.
 *
 * <p>The service is mocked on purpose. What is under test is the wiring from a
 * thrown {@code LedgerPostingException} to an HTTP status and a {@code code} on
 * the wire — not which exception the service picks, which
 * {@code LedgerCloseServiceTest} covers against a real database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("A close's 422 arrives as a 422, carrying its code")
class AccountsControllerErrorsTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LedgerCloseService closeService;

    private static final String A_COUNT = """
            {"reconDate":"2026-11-11","countedAmount":3392.00}
            """;

    // ------------------------------------------------------------------
    // The advice is reached at all
    // ------------------------------------------------------------------

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("⚠ poolCode = SAFE — the refusal that stands between the seed and a self-contra")
    void safeIsNotAPool() throws Exception {
        // V21:331 seeds CASH_SAFE into pool SAFE, and the seed is what is wrong
        // (ADR 0002). Closing it as a pool of one would post a Dr CASH_SAFE /
        // Cr CASH_SAFE contra every night. Until S6 clears the column this 422 is
        // the only thing stopping that, so it is tested on the wire and not only
        // in the service.
        Mockito.when(closeService.closePool(eq("SAFE"), any(), any()))
                .thenThrow(new LedgerPostingException(
                        "CASH_SAFE closes as a single account, not as a pool: POST "
                                + "/accounts/CASH_SAFE/reconciliations (endpoint 4)."));

        mockMvc.perform(post("/accounts/pools/SAFE/closes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(A_COUNT))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(ErrorCode.LEDGER_POSTING_REFUSED.name()))
                // The sentence matters as much as the code: it has to say where
                // to go instead.
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        "/accounts/CASH_SAFE/reconciliations")))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("a future reconDate is a 422, not a bare 500")
    void futureReconDate() throws Exception {
        Mockito.when(closeService.closePool(anyString(), any(), any()))
                .thenThrow(new LedgerPostingException(
                        "reconDate 2027-01-01 is in the future (today is 2026-11-12)."));

        mockMvc.perform(post("/accounts/pools/POOL-1/closes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(A_COUNT))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(ErrorCode.LEDGER_POSTING_REFUSED.name()))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("in the future")));
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("a variance that does not match its explanation is a 422")
    void varianceMismatch() throws Exception {
        Mockito.when(closeService.closePool(anyString(), any(), any()))
                .thenThrow(new LedgerPostingException(
                        "explained.amount (20.00) must equal the whole variance (50.00)."));

        mockMvc.perform(post("/accounts/pools/POOL-1/closes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reconDate":"2026-11-11","countedAmount":4950.00,
                                 "explained":{"direction":"OUT",
                                              "accountCode":"EXP_SUNDRY_SHOP","amount":20.00}}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(ErrorCode.LEDGER_POSTING_REFUSED.name()));
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("⚠ the LOCKED refusal keeps its own code, because the next action differs")
    void lockedByReconciliation() throws Exception {
        // The one ledger refusal a client branches on: the day is sealed, so the
        // route is a correcting JOURNAL rather than a delete-and-repost (FRD
        // §8.3). Every other refusal's sentence is the whole of what a client can
        // act on, which is why there are two codes and not twenty.
        Mockito.when(closeService.closeAccount(anyString(), any(), any()))
                .thenThrow(new LedgerPostingException(
                        ErrorCode.VOUCHER_LOCKED_BY_RECONCILIATION,
                        "CASH_SAFE already reconciled on or after 2026-11-11. Post a "
                                + "correcting JOURNAL instead (FRD 8.3)."));

        mockMvc.perform(post("/accounts/CASH_SAFE/reconciliations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(A_COUNT))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code")
                        .value(ErrorCode.VOUCHER_LOCKED_BY_RECONCILIATION.name()));
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("an unknown account on the history read is a 422 with a code, not an empty page")
    void unknownAccountOnHistory() throws Exception {
        Mockito.when(closeService.history(anyString(), any(), any(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new LedgerPostingException("no such account: BANK_NOPE"));

        mockMvc.perform(get("/accounts/BANK_NOPE/reconciliations"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(ErrorCode.LEDGER_POSTING_REFUSED.name()));
    }

    // ------------------------------------------------------------------
    // The replay, which is the other half of the contract
    // ------------------------------------------------------------------

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("⚠ a replayed close is 409 CARRYING the original, never a bare error body")
    void replayedPoolCloseCarriesTheOriginal() throws Exception {
        // §1.1's code list says 409 "already closed for that date" and §1.3 says
        // the service returns "the original close rather than a second sweep" —
        // both hold only if the 409 carries the record. The precedent is
        // RechargeController:593, where a duplicate mobile answers 409 with the
        // EXISTING customer: a bare 409 would leave the operator stuck, here with
        // a counted drawer and no figures to compare it against.
        Mockito.when(closeService.closePool(anyString(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("uk_recon_account_date"));
        Mockito.when(closeService.replayedPoolClose(eq("POOL-1"), eq(LocalDate.of(2026, 11, 11))))
                .thenReturn(Optional.of(new PoolCloseResponse(
                        "POOL-1", LocalDate.of(2026, 11, 11),
                        new BigDecimal("3392.00"), new BigDecimal("3392.00"),
                        new BigDecimal("0.00"), null,
                        new Sweep(77L, new BigDecimal("3392.00"), Sweep.SweepStatus.POSTED),
                        List.of(), true)));

        mockMvc.perform(post("/accounts/pools/POOL-1/closes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(A_COUNT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.countedAmount").value(3392.00))
                .andExpect(jsonPath("$.sweep.voucherId").value(77));
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("⚠ a constraint that is NOT a known close is rethrown, not reported as done")
    void anUnrelatedConstraintIsNotAReplay() throws Exception {
        Mockito.when(closeService.closePool(anyString(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("fk_recon_account"));
        Mockito.when(closeService.replayedPoolClose(anyString(), any()))
                .thenReturn(Optional.empty());

        // Reaches the handler's DataIntegrityViolationException branch as a 409
        // DATA_INTEGRITY_VIOLATION rather than being dressed up as an
        // already-closed day with figures nobody wrote.
        mockMvc.perform(post("/accounts/pools/POOL-1/closes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(A_COUNT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value(ErrorCode.DATA_INTEGRITY_VIOLATION.name()));
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("a replayed single-account close is 409 carrying its row")
    void replayedAccountClose() throws Exception {
        Mockito.when(closeService.closeAccount(anyString(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("uk_recon_account_date"));
        Mockito.when(closeService.replayedAccountClose(eq("CASH_SAFE"),
                        eq(LocalDate.of(2026, 11, 11))))
                .thenReturn(Optional.of(new ReconciliationResponse(
                        9L, "CASH_SAFE", LocalDate.of(2026, 11, 11),
                        new BigDecimal("21795.00"), new BigDecimal("21795.00"),
                        new BigDecimal("0.00"), null, null, null, null, null)));

        mockMvc.perform(post("/accounts/CASH_SAFE/reconciliations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(A_COUNT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reconId").value(9))
                .andExpect(jsonPath("$.accountCode").value("CASH_SAFE"));
    }

    // ------------------------------------------------------------------
    // The header convention's two exceptions
    // ------------------------------------------------------------------

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("⚠ no Idempotency-Key header is required, and sending one changes nothing")
    void noIdempotencyKeyHeader() throws Exception {
        // §1.3's deliberate exception to design-api.md §1.1. "Close POOL-1 for 9
        // Nov" is true at most once, so the key is server-derived and
        // uk_recon_account_date is the guard. A required header whose value the
        // server ignores is worse than none, because it tells a client the value
        // matters and someone will send a fresh one per click — which would
        // defeat the replay rather than enable it.
        Mockito.when(closeService.closePool(anyString(), any(), any()))
                .thenReturn(new PoolCloseResponse(
                        "POOL-1", LocalDate.of(2026, 11, 11),
                        new BigDecimal("3392.00"), new BigDecimal("3392.00"),
                        new BigDecimal("0.00"), null,
                        new Sweep(5L, new BigDecimal("3392.00"), Sweep.SweepStatus.POSTED),
                        List.of(), false));

        mockMvc.perform(post("/accounts/pools/POOL-1/closes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(A_COUNT))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/accounts/pools/POOL-1/closes")
                        .header("Idempotency-Key", "ignored-entirely")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(A_COUNT))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("⚠ a missing reconDate is a 400 and nothing is written")
    void reconDateIsRequired() throws Exception {
        // It must never default server-side to today. The lock test is
        // RECON_DATE >= VOUCHER_DATE, so a close at 00:30 dated today seals every
        // one of yesterday's vouchers while yesterday never receives a count —
        // sealed, uncounted and unamendable. A 400 is the only safe answer; the
        // screen defaults it to the oldest unclosed day.
        mockMvc.perform(post("/accounts/pools/POOL-1/closes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"countedAmount":3392.00}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_REQUEST.name()));

        Mockito.verify(closeService, Mockito.never()).closePool(anyString(), any(), any());
    }
}
