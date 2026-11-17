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

import com.mangle.retailshopapp.accounting.LedgerPostingException;
import com.mangle.retailshopapp.accounting.ManualVoucherService;
import com.mangle.retailshopapp.accounting.model.response.VoucherResponse;
import com.mangle.retailshopapp.apierror.ErrorCode;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ⚠ THE GUARD ON {@code RechargeExceptionHandler}'s {@code assignableTypes}, for
 * {@code VoucherController} and {@code ConfigController}.
 *
 * <p>That advice is scoped to named controllers and <b>this application has no
 * global advice</b> — making it global would change the error shape of every
 * existing endpoint, including the ones the water mobile app calls. A controller
 * missing from the list gets Spring's default error body instead: every 422 below
 * would arrive as a bare 500 with no {@code code} on it, and the missing-header
 * 400 would arrive as a 500 too.
 *
 * <p>This is the only kind of test that can see that.
 * {@code ManualVoucherServiceTest} passes either way, because the service throws
 * the right exception either way — <b>the failure is entirely in whether anything
 * is listening</b>, and nothing about adding a controller prompts you to go and
 * amend the advice. S4 shipped exactly this defect once and found it by running
 * the app rather than by its 349 tests.
 *
 * <p><b>Verified by reverting the annotation, 2026-11-16: 9 of these 13 tests
 * fail.</b> Seven error out — MockMvc rethrows what nothing handled, which on a
 * real server is the bare 500 — and two fail on the status line,
 * {@code missingIdempotencyKeyIs400} and {@code bodyValidationNamesTheField}
 * both getting 500 where a client's own mistake should be a 400. The S4
 * equivalent was 7 of 8 and the close's was 7 of 10; recorded because a guard
 * test that has never been watched to fail is not a guard.
 *
 * <p>⚠ <b>THE THREE THAT STILL PASS SAY SOMETHING TOO, AND ONE OF THEM IS A
 * LIMIT OF THIS FILE.</b> The two replay cases pass because the controller
 * produces those 409s itself with no exception in the path — correct. But
 * {@code configRespondsAndIsNotA500} passes either way, because
 * <b>{@code ConfigController}'s membership of that list is not observable
 * behaviour today</b>: it takes no parameters and no body and throws nothing of
 * its own, so there is no error shape for the advice to change. Its membership is
 * asserted on the annotation instead ({@code configControllerIsNamedInTheAdvice}),
 * which is a regression guard rather than a behaviour test, and is labelled as
 * one. Writing a behavioural test that passed either way and calling it a guard
 * is exactly the failure next-session-s5.md keeps warning about.
 *
 * <p>The service is mocked on purpose. What is under test is the wiring from a
 * thrown {@code LedgerPostingException} to a status and a {@code code} on the
 * wire — not which exception a service picks, which
 * {@code ManualVoucherServiceTest} covers against a real database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("A voucher's 422 arrives as a 422, carrying its code")
class VoucherControllerErrorsTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ManualVoucherService vouchers;

    private static final String A_PAYMENT = """
            {"voucherDate":"2026-11-16","expenseAccountCode":"EXP_ELEC_WATER",
             "sourceAccountCode":"CASH_SAFE","amount":1200.00,"narration":"MSEB bill, Sept"}
            """;

    private static final String A_CONTRA = """
            {"voucherDate":"2026-11-16","fromAccountCode":"CASH_SAFE",
             "toAccountCode":"BANK_JANATA","amount":5000.00,"narration":"Weekly deposit"}
            """;

    private static final String A_JOURNAL = """
            {"voucherDate":"2026-11-16",
             "legs":[{"accountCode":"EXP_MAINT_WATER","dr":1200.00},
                     {"accountCode":"EXP_ELEC_WATER","cr":1200.00}],
             "narration":"was pump maintenance"}
            """;

    private static final String A_PURCHASE = """
            {"voucherDate":"2026-11-16","floatAccountCode":"FLOAT_A1TOPUP","amount":10000.00,
             "sourceAccountCode":"BANK_JANATA","narration":"IMPS to A1Topup"}
            """;

    // ------------------------------------------------------------------
    // One per endpoint: the advice is reached at all
    // ------------------------------------------------------------------

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("#7 an expense refused for its category is a 422, not a bare 500")
    void paymentRefusal() throws Exception {
        Mockito.when(vouchers.postPayment(any(), anyString(), any()))
                .thenThrow(new LedgerPostingException(
                        "CASH_SAFE is ASSET, and an expense needs an EXPENSE account. "
                                + "GET /accounts?manualOnly=true&type=EXPENSE lists the ones "
                                + "this endpoint accepts."));

        perform("/vouchers/payments", A_PAYMENT)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(ErrorCode.LEDGER_POSTING_REFUSED.name()))
                // The sentence matters as much as the code: it has to say where
                // to find a legal account.
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("manualOnly=true")))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("#8 a self-contra is a 422")
    void contraRefusal() throws Exception {
        Mockito.when(vouchers.postContra(any(), anyString(), any()))
                .thenThrow(new LedgerPostingException(
                        "fromAccountCode and toAccountCode are both CASH_SAFE. A Dr X / Cr X "
                                + "contra balances, passes every check and moves nothing."));

        perform("/vouchers/contras", A_CONTRA)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(ErrorCode.LEDGER_POSTING_REFUSED.name()))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("moves nothing")));
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("#9 an unbalanced journal keeps both totals in its sentence")
    void journalRefusal() throws Exception {
        Mockito.when(vouchers.postJournal(any(), anyString(), any()))
                .thenThrow(new LedgerPostingException(
                        "the journal does not balance: DR 50.00 vs CR 40.00. The difference "
                                + "is 10.00."));

        perform("/vouchers/journals", A_JOURNAL)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(ErrorCode.LEDGER_POSTING_REFUSED.name()))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("DR 50.00 vs CR 40.00")));
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("⚠ #9 an A7 refusal names the endpoint that DOES move the account")
    void journalA7Refusal() throws Exception {
        // The refusal a screen is most likely to hit, and the one where a bare
        // 500 would be worst: the client's next action is a different ENDPOINT,
        // not a different account, and only the message says which.
        Mockito.when(vouchers.postJournal(any(), anyString(), any()))
                .thenThrow(new LedgerPostingException(
                        "UPI_CLEARING is ALLOW_MANUAL = FALSE and cannot carry a hand-posted "
                                + "leg (A7). UPI_CLEARING is settled by POST "
                                + "/accounts/upi-clearing/settlements (endpoint 6)."));

        perform("/vouchers/journals", A_JOURNAL)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(ErrorCode.LEDGER_POSTING_REFUSED.name()))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString(
                                "/accounts/upi-clearing/settlements")));
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("#19 a top-up on a non-float is a 422")
    void purchaseRefusal() throws Exception {
        Mockito.when(vouchers.postPurchase(any(), anyString(), any()))
                .thenThrow(new LedgerPostingException(
                        "CASH_SAFE is not a float account: it has no FLOAT_PATTERN, which is "
                                + "what makes an account a wallet (V21)."));

        perform("/vouchers/purchases", A_PURCHASE)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(ErrorCode.LEDGER_POSTING_REFUSED.name()))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("FLOAT_PATTERN")));
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("⚠ the sealed-day refusal keeps its OWN code, which a client branches on")
    void lockedVoucherKeepsItsCode() throws Exception {
        // VOUCHER_LOCKED_BY_RECONCILIATION is the one refusal with a different
        // next action — a correcting JOURNAL rather than a delete-and-repost
        // (FRD §8.3) — so the code has to survive the trip and not be flattened
        // into the general one.
        Mockito.when(vouchers.postPurchase(any(), anyString(), any()))
                .thenThrow(new LedgerPostingException(
                        ErrorCode.VOUCHER_LOCKED_BY_RECONCILIATION,
                        "cannot delete voucher 8812: CASH_RECHARGE already reconciled on or "
                                + "after 2026-10-31. Post a correcting JOURNAL instead "
                                + "(FRD 8.3)."));

        perform("/vouchers/purchases", A_PURCHASE)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code")
                        .value(ErrorCode.VOUCHER_LOCKED_BY_RECONCILIATION.name()));
    }

    // ------------------------------------------------------------------
    // The header, the validation and the replay
    // ------------------------------------------------------------------

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("⚠ no Idempotency-Key is a 400 — through the advice, not by accident")
    void missingIdempotencyKeyIs400() throws Exception {
        // MissingRequestHeaderException is handled at RechargeExceptionHandler's
        // handleMissing. Without this controller in assignableTypes it falls to
        // Spring's default and the client gets a 500 for its own mistake.
        mockMvc.perform(post("/vouchers/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(A_PAYMENT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_REQUEST.name()))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("Idempotency-Key")));
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("a missing narration on an expense is a 400 with the field named")
    void bodyValidationNamesTheField() throws Exception {
        // narration is required because it is the ONLY record of what the money
        // was for — "₹1,200" with no sentence is unreadable six months later.
        mockMvc.perform(post("/vouchers/payments")
                        .header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"voucherDate":"2026-11-16",
                                 "expenseAccountCode":"EXP_ELEC_WATER",
                                 "sourceAccountCode":"CASH_SAFE","amount":1200.00}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_REQUEST.name()))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("narration"));
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("a replay is a 409 carrying the original voucher")
    void replayIs409() throws Exception {
        Mockito.when(vouchers.postPayment(any(), anyString(), any()))
                .thenReturn(replayedVoucher());

        perform("/vouchers/payments", A_PAYMENT)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.voucherId").value(8812))
                .andExpect(jsonPath("$.replayed").value(true))
                // ⚠ The body is the original, which is what makes the 409
                // actionable: the person pressed twice and needs to see what
                // exists, not a bare error.
                .andExpect(jsonPath("$.legs[0].accountCode").value("EXP_ELEC_WATER"));
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("⚠ the duplicate-key race is a 409 too, re-read in a fresh transaction")
    void concurrentFirstPostIs409() throws Exception {
        // Two concurrent FIRST posts: both passed the service's lookup and
        // uk_vch_idem refused the second. The transaction has rolled back, so the
        // winner is only visible from a fresh read — which is what replayed() is.
        Mockito.when(vouchers.postPayment(any(), anyString(), any()))
                .thenThrow(new DataIntegrityViolationException("uk_vch_idem"));
        Mockito.when(vouchers.replayed(anyString())).thenReturn(Optional.of(replayedVoucher()));

        perform("/vouchers/payments", A_PAYMENT)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.voucherId").value(8812));
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("⚠ an integrity failure that is NOT a replay stays an error")
    void aGenuineIntegrityFailureIsNotReportedAsDone() throws Exception {
        // The one thing this catch must never do is report a constraint failure
        // as an already-done. 409 DATA_INTEGRITY_VIOLATION is the handler's
        // answer, and it is not a 201.
        Mockito.when(vouchers.postPayment(any(), anyString(), any()))
                .thenThrow(new DataIntegrityViolationException("fk_line_account"));
        Mockito.when(vouchers.replayed(anyString())).thenReturn(Optional.empty());

        perform("/vouchers/payments", A_PAYMENT)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.DATA_INTEGRITY_VIOLATION.name()))
                .andExpect(jsonPath("$.replayed").doesNotExist());
    }

    // ------------------------------------------------------------------
    // ConfigController is in the list too
    // ------------------------------------------------------------------

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("/config answers, and a POST to it is a 405 rather than a 500")
    void configRespondsAndIsNotA500() throws Exception {
        // ⚠ THIS PASSES WITH OR WITHOUT ConfigController IN assignableTypes, and
        // that is recorded rather than hidden: Spring answers the 405 itself.
        // The controller takes no input and throws nothing, so the advice has no
        // error shape to change — see the class comment.
        mockMvc.perform(post("/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed());

        mockMvc.perform(get("/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changeDisplayThreshold").exists());
    }

    @Test
    @DisplayName("⚠ both new controllers are NAMED in the advice — a regression guard")
    void configControllerIsNamedInTheAdvice() {
        // Not a behaviour test and not pretending to be one. ConfigController's
        // membership cannot be observed through HTTP today, so the only honest
        // way to stop someone deleting it is to assert the annotation. It also
        // documents the full list in one place a reader can check.
        List<Class<?>> named = List.of(
                org.springframework.core.annotation.AnnotationUtils.findAnnotation(
                        com.mangle.retailshopapp.apierror.RechargeExceptionHandler.class,
                        org.springframework.web.bind.annotation.RestControllerAdvice.class)
                        .assignableTypes());

        org.junit.jupiter.api.Assertions.assertTrue(named.contains(VoucherController.class),
                "VoucherController must be named, or every 422 above is a bare 500");
        org.junit.jupiter.api.Assertions.assertTrue(
                named.contains(com.mangle.retailshopapp.settings.ConfigController.class),
                "ConfigController must be named, so the call every screen makes first "
                        + "fails in the same shape as the rest");
        // And the three the earlier slices added are still there: this list is
        // append-only in practice, and a removal would be silent.
        org.junit.jupiter.api.Assertions.assertTrue(named.contains(AccountsController.class));
        org.junit.jupiter.api.Assertions.assertEquals(5, named.size(),
                "a sixth controller means a sixth guard test — and NOT a global advice, "
                        + "which would change the error shape of every existing endpoint");
    }

    // ------------------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions perform(String path, String body)
            throws Exception {
        return mockMvc.perform(post(path)
                .header("Idempotency-Key", "11111111-2222-3333-4444-555555555555")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static VoucherResponse replayedVoucher() {
        return new VoucherResponse(
                8812L, "PAYMENT", LocalDate.of(2026, 11, 16),
                LocalDate.of(2026, 11, 16).atTime(16, 20), "MSEB bill, Sept",
                "MANUAL", null, null, LocalDate.of(2026, 11, 16).atTime(16, 20),
                List.of(new VoucherResponse.Leg((short) 1, "EXP_ELEC_WATER",
                                new BigDecimal("1200.00"), new BigDecimal("0.00"),
                                null, null, null),
                        new VoucherResponse.Leg((short) 2, "CASH_SAFE",
                                new BigDecimal("0.00"), new BigDecimal("1200.00"),
                                null, null, null)),
                true);
    }
}
