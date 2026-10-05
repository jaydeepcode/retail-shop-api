package com.mangle.retailshopapp.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mangle.retailshopapp.user.controller.AuthenticationRequest;

/**
 * Registration and authentication against the in-memory test profile.
 *
 * <p>Two things here are deliberate, because both were defects that stopped this class running:
 *
 * <ul>
 *   <li><b>Each test creates its own user, with its own username, contact number and vehicle
 *       number</b> — registration rejects a duplicate of any of the three. The suite
 *       shares one Spring context, so the H2 schema is created once and data written by one test is
 *       visible to the next. Previously only {@code testCustomerRegistration} registered anyone,
 *       which left {@code testAuthenticationWithPendingAccount} depending on JUnit's method order —
 *       and it runs first, so it authenticated a user that did not exist and got 401, not 403.
 *   <li><b>Paths carry no {@code /api} prefix.</b> {@code server.servlet.context-path=/api} applies
 *       to the running server, but MockMvc does not apply it, so a prefixed path matches no
 *       security rule and falls through to {@code anyRequest().authenticated()} as a 403.
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class AuthenticationIntegrationTest {

    private static final String PASSWORD = "testpass123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static String registrationJson(String username, String contactNumber, String vehicleNumber) {
        return """
            {
                "username": "%s",
                "password": "%s",
                "firstName": "Test",
                "lastName": "Customer",
                "storageType": "Tanker",
                "tankerCapacity": 5000,
                "vehicleNumber": "%s",
                "address": "Test Address",
                "contactNumber": "%s",
                "location": "Chennai"
            }
            """.formatted(username, PASSWORD, vehicleNumber, contactNumber);
    }

    private void register(String username, String contactNumber, String vehicleNumber) throws Exception {
        mockMvc.perform(post("/customer/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registrationJson(username, contactNumber, vehicleNumber)))
                .andExpect(status().isOk());
    }

    @Test
    public void testCustomerRegistration() throws Exception {
        mockMvc.perform(post("/customer/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registrationJson("registertestuser", "9876543210", "TN01AB1234")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message")
                        .value("Registration submitted successfully. Please wait for admin approval."));
    }

    @Test
    public void testAuthenticationWithPendingAccount() throws Exception {
        register("pendingtestuser", "9876543211", "TN01AB9999");

        AuthenticationRequest authRequest =
                new AuthenticationRequest("pendingtestuser", PASSWORD, "Test-Device");

        mockMvc.perform(post("/authenticate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(authRequest)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Account pending approval"));
    }
}
