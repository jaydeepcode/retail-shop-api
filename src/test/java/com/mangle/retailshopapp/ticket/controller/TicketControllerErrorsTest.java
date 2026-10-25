package com.mangle.retailshopapp.ticket.controller;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.mangle.retailshopapp.ticket.exception.BookingNotFoundException;
import com.mangle.retailshopapp.ticket.exception.InvalidBookingException;
import com.mangle.retailshopapp.ticket.exception.TicketFundingMismatchException;
import com.mangle.retailshopapp.ticket.exception.UnknownFundingAccountException;
import com.mangle.retailshopapp.ticket.service.TicketBookingService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ⚠ THE GUARD ON {@code RechargeExceptionHandler}'s {@code assignableTypes}.
 *
 * <p>That advice is scoped to named controllers rather than applied globally, and
 * the application has NO global advice. So a controller missing from that list
 * gets Spring's default error body instead: every 422 below would arrive as a
 * bare 500 with no {@code code} on it.
 *
 * <p>This is the only kind of test that can see that. A service unit test passes
 * either way, because the service throws the right exception either way — the
 * failure is entirely in whether anything is listening. It was a real risk on
 * this slice: {@code TicketController} did not exist when the advice was
 * written, and nothing about adding a controller prompts you to go and amend it.
 *
 * <p>The service is mocked on purpose. What is under test is the wiring from a
 * thrown {@code RechargeException} to an HTTP status and a {@code code} on the
 * wire — not which exception the service picks, which
 * {@code TicketBookingServiceTest} covers against a real database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("A ticket 422 arrives as a 422, carrying its code")
class TicketControllerErrorsTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TicketBookingService bookingService;

    /** Flow 1, well-formed — so only the stubbed exception decides the outcome. */
    private static final String FLOW_ONE = """
            {"bookingMode":"RAIL","passengerCount":1,"fareAmount":425.00,
             "commissionAmount":60.00,"fundingAccountCode":"BANK_SELF",
             "settlementLines":[{"method":"CASH","amount":485.00,"amountTendered":500.00}]}
            """;

    private org.springframework.test.web.servlet.ResultActions postBooking(RuntimeException toThrow)
            throws Exception {
        Mockito.when(bookingService.book(any(), any(), any(), any(), any(), any(), any(),
                        any(), anyString()))
                .thenThrow(toThrow);
        return mockMvc.perform(post("/tickets/bookings")
                .header("Idempotency-Key", "11111111-2222-3333-4444-555555555555")
                .contentType(MediaType.APPLICATION_JSON)
                .content(FLOW_ONE));
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("⚠ a fare with no funding account — ck_tkt_funding restated at the edge")
    void fundingMismatch() throws Exception {
        postBooking(new TicketFundingMismatchException(true))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TICKET_FUNDING_MISMATCH"))
                // The sentence matters as much as the code: the staff member has
                // to know which of the two fields to fill in.
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("choose a funding account")))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("an unknown funding account names the four that are allowed")
    void unknownFundingAccount() throws Exception {
        postBooking(new UnknownFundingAccountException("BANK_HDFC",
                List.of("BANK_JANATA", "BANK_SELF", "BANK_WIFE", "BANK_FATHER")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNKNOWN_FUNDING_ACCOUNT"))
                .andExpect(jsonPath("$.details.permitted").isArray())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("BANK_SELF")));
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("a passenger count below one is a 422, not a bean-validation 400")
    void passengerCount() throws Exception {
        // §4 lists this among the 422 conditions, "a rule from this document
        // rather than a generic validation" — so the field carries no @Min and
        // this is the layer that refuses it.
        postBooking(InvalidBookingException.passengerCount(0))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_PASSENGER_COUNT"))
                .andExpect(jsonPath("$.details.passengerCount").value(0));
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("negative commission is refused, and the message says zero is fine")
    void negativeCommission() throws Exception {
        postBooking(InvalidBookingException.commission(new BigDecimal("-1.00")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_COMMISSION"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Zero is allowed")));
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("⚠ the tender rules reach the wire through the SHARED hierarchy")
    void linesDoNotSum() throws Exception {
        // Thrown by TenderPlan, which knows nothing about tickets. It arrives
        // with the same code recharge uses, which is §8.4's whole point: a
        // counter deciding where to put the cursor cannot do it from two
        // vocabularies.
        postBooking(new com.mangle.retailshopapp.recharge.exception.LinesDoNotSumException(
                new BigDecimal("485.00"), new BigDecimal("400.00")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LINES_DO_NOT_SUM"))
                .andExpect(jsonPath("$.details.expectedTotal").value(485.00))
                .andExpect(jsonPath("$.details.providedTotal").value(400.00));
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("a credit line with no customer is the one hard credit rule")
    void creditRequiresCustomer() throws Exception {
        postBooking(new com.mangle.retailshopapp.recharge.exception
                .CreditRequiresCustomerException())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CREDIT_REQUIRES_CUSTOMER"));
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("an unknown booking is a 404 with a code, not an empty body")
    void notFound() throws Exception {
        Mockito.when(bookingService.byId(anyLong()))
                .thenThrow(new BookingNotFoundException(4040));

        mockMvc.perform(get("/tickets/bookings/4040"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOKING_NOT_FOUND"))
                .andExpect(jsonPath("$.details.bookingId").value(4040));
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_USER"})
    @DisplayName("a missing Idempotency-Key is refused before anything is written")
    void missingIdempotencyKey() throws Exception {
        mockMvc.perform(post("/tickets/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FLOW_ONE))
                .andExpect(status().isBadRequest());

        Mockito.verify(bookingService, Mockito.never())
                .book(any(), any(), any(), any(), any(), any(), any(), any(), anyString());
    }
}
