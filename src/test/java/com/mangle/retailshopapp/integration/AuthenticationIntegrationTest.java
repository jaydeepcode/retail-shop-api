package com.mangle.retailshopapp.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mangle.retailshopapp.user.controller.AuthenticationRequest;

@SpringBootTest
@AutoConfigureWebMvc
@ActiveProfiles("test")
public class AuthenticationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    public void testCustomerRegistration() throws Exception {
        String registrationJson = """
            {
                "username": "testcustomer",
                "password": "testpass123",
                "firstName": "Test",
                "lastName": "Customer",
                "storageType": "Tanker",
                "tankerCapacity": 5000,
                "vehicleNumber": "TN01AB1234",
                "address": "Test Address",
                "contactNumber": "9876543210",
                "location": "Chennai"
            }
            """;

        mockMvc.perform(post("/api/customer/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registrationJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Registration submitted successfully. Please wait for admin approval."));
    }

    @Test
    public void testAuthenticationWithPendingAccount() throws Exception {
        AuthenticationRequest authRequest = new AuthenticationRequest("testcustomer", "testpass123", "Test-Device");
        String authJson = objectMapper.writeValueAsString(authRequest);

        mockMvc.perform(post("/api/authenticate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(authJson))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Account pending approval"));
    }
}

