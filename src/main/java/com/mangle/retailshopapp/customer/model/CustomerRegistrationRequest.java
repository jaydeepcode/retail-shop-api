package com.mangle.retailshopapp.customer.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CustomerRegistrationRequest {
    private Integer customerId; // Optional, populated when mobile already exists

    @NotBlank(message = "Username is required")
    @Size(min = 4, max = 45, message = "Username must be between 4 and 45 characters")
    private String username;

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 80, message = "Password must be between 8 and 80 characters")
    private String password;

    @NotBlank(message = "First name is required")
    @Size(max = 45, message = "First name must be at most 45 characters")
    private String firstName;

    @NotBlank(message = "Last name is required")
    @Size(max = 45, message = "Last name must be at most 45 characters")
    private String lastName;

    @NotBlank(message = "Storage type is required")
    private String storageType;

    @NotNull(message = "Tanker capacity is required")
    @Positive(message = "Tanker capacity must be positive")
    private Integer tankerCapacity;

    @NotBlank(message = "Vehicle number is required")
    @Size(max = 20, message = "Vehicle number must be at most 20 characters")
    private String vehicleNumber;

    @NotBlank(message = "Address is required")
    private String address;

    @NotBlank(message = "Contact number is required")
    @Pattern(regexp = "^[0-9]{10}$", message = "Contact number must be 10 digits")
    private String contactNumber;

    @NotBlank(message = "Location is required")
    private String location;
}
