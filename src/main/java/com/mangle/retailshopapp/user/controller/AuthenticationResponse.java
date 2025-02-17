package com.mangle.retailshopapp.user.controller;

import lombok.Data;

@Data
public class AuthenticationResponse {
    private final String jwt;
}
