package com.mangle.retailshopapp.user.controller;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class AuthenticationRequest {
 
    private String username;
    private String password;
    private String deviceInfo; // Optional: e.g., "PC-Chrome", "Mobile-Android", "iPhone-App"
}
