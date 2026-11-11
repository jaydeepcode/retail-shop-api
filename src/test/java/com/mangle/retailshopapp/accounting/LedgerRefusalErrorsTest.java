package com.mangle.retailshopapp.accounting;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.mangle.retailshopapp.apierror.ErrorCode;
import com.mangle.retailshopapp.ticket.service.TicketBookingService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ⚠ A DRAFT THE POSTING RULES REFUSE ARRIVES AS A 422 CARRYING ITS SENTENCE.
 *
 * <p>Until S5 it arrived as <b>500 {@code INTERNAL_ERROR}, "An unexpected error
 * occurred."</b> {@code LedgerPostingException} extended {@code RuntimeException}
 * with no {@code ErrorCode}, so it matched no handler in
 * {@code RechargeExceptionHandler} and fell to the catch-all at the end of it,
 * which logs the stack trace against a trace id and returns a fixed string. Its
 * own javadoc said it existed "to return a sentence rather than a raw
 * {@code SIGNAL SQLSTATE '45000'}", and it returned neither.
 *
 * <p><b>Verified by making it fail.</b> With the fix reverted — the class back on
 * {@code extends RuntimeException} — both assertions below fail on the status
 * line, 500 against the expected 422, and the second also loses the whole
 * message. That check is the point of the test rather than a note about it: the
 * tally in next-session-s5.md is that every defect on this project was found by
 * running the thing, and a guard test that passes either way is exactly how this
 * defect shipped the first time.
 *
 * <p><b>Why this is a different failure from {@code assignableTypes}.</b>
 * {@code TicketControllerErrorsTest} guards the other one — a controller missing
 * from the advice's {@code assignableTypes} list, where nothing is listening at
 * all. Here the advice IS reached and is correctly scoped; what was missing was a
 * handler for this exception's type. The two are indistinguishable on the wire,
 * they have different causes and different fixes, and item 4 of the brief does
 * not fix item 5. Hence a separate test.
 *
 * <p><b>Why the ticket endpoint is the vehicle.</b> The ledger's own controllers
 * do not exist yet — they are steps 6 and 7 of this slice — and the exception has
 * to reach an HTTP response through a controller the advice already covers to be
 * observed at all. {@code TicketBookingService} is a real caller of
 * {@code LedgerPostingService}, so a refusal escaping it is the actual shape of
 * the bug, not a staged one. The service is mocked for the same reason S4 mocked
 * it: what is under test is the wiring from a thrown exception to a status and a
 * {@code code} on the wire, not which exception a service picks.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("A refused ledger draft arrives as a 422, not a bare 500")
class LedgerRefusalErrorsTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TicketBookingService bookingService;

    /** Well-formed, so only the stubbed refusal decides the outcome. */
    private static final String FLOW_ONE = """
            {"bookingMode":"RAIL","passengerCount":1,"fareAmount":425.00,
             "commissionAmount":60.00,"fundingAccountCode":"BANK_SELF",
             "settlementLines":[{"method":"CASH","amount":485.00,"amountTendered":500.00}]}
            """;

    private ResultActions postBooking(RuntimeException toThrow) throws Exception {
        Mockito.when(bookingService.book(any(), any(), any(), any(), any(), any(), any(),
                        any(), anyString()))
                .thenThrow(toThrow);
        return mockMvc.perform(post("/tickets/bookings")
                .header("Idempotency-Key", "99999999-8888-7777-6666-555555555555")
                .contentType(MediaType.APPLICATION_JSON)
                .content(FLOW_ONE));
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("⚠ an unbalanced draft keeps both totals in its sentence")
    void unbalancedDraftIs422() throws Exception {
        // The real message from LedgerPostingService's balance check. It names
        // both sides because the person reading it has to find the missing leg,
        // and the catch-all threw exactly this detail away.
        postBooking(new LedgerPostingException(
                "voucher does not balance: DR 485.00 vs CR 425.00"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(ErrorCode.LEDGER_POSTING_REFUSED.name()))
                .andExpect(jsonPath("$.message").value(
                        "voucher does not balance: DR 485.00 vs CR 425.00"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("⚠ the sealed-day refusal carries its own code and points at the journal")
    void lockedVoucherHasItsOwnCode() throws Exception {
        // LedgerCorrectionRules.assertVoucherDeletable's message, verbatim. This
        // is the one refusal whose code a client branches on: the day is sealed,
        // so the route is a correcting JOURNAL and not a delete-and-repost
        // (FRD 8.3), and ticket-corrections.md has the ticket module choosing
        // between exactly those two mechanisms.
        postBooking(new LedgerPostingException(
                ErrorCode.VOUCHER_LOCKED_BY_RECONCILIATION,
                "cannot delete voucher 8812: CASH_RECHARGE already reconciled on or after "
                        + "2026-10-31. Post a correcting JOURNAL instead (FRD 8.3)."))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code")
                        .value(ErrorCode.VOUCHER_LOCKED_BY_RECONCILIATION.name()))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("correcting JOURNAL")))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("CASH_RECHARGE")));
    }

    /**
     * The status is carried by the tree, so a refusal cannot be a 500 by
     * accident.
     *
     * <p>{@code RechargeException} holds its own status
     * (design-api.md §8.2) and one {@code @ExceptionHandler} covers the whole
     * tree, which is what makes the two assertions above hold for every one of
     * the ~20 refusal messages rather than only for the two sampled. This is the
     * cheap unit-level restatement of that; the MockMvc tests prove the wiring.
     */
    @Test
    @DisplayName("every refusal is a 422 on the exception itself, sampled or not")
    void statusLivesOnTheException() {
        LedgerPostingException ordinary = new LedgerPostingException("a leg cannot be zero: CASH_SAFE");
        org.junit.jupiter.api.Assertions.assertEquals(422, ordinary.status());
        org.junit.jupiter.api.Assertions.assertEquals(
                ErrorCode.LEDGER_POSTING_REFUSED, ordinary.code());

        LedgerPostingException locked = new LedgerPostingException(
                ErrorCode.VOUCHER_LOCKED_BY_RECONCILIATION, "sealed on " + LocalDate.of(2026, 10, 31));
        org.junit.jupiter.api.Assertions.assertEquals(422, locked.status());

        // Guards the inheritance itself: a later edit putting this class back on
        // RuntimeException compiles everywhere except here.
        org.junit.jupiter.api.Assertions.assertInstanceOf(
                com.mangle.retailshopapp.apierror.RechargeRuleViolationException.class, locked);
    }
}
