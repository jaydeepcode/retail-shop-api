package com.mangle.retailshopapp.user.controller;

import lombok.Data;

@Data
public class AuthenticationResponse {
    private final String accessToken;
    private final String refreshToken;
}
