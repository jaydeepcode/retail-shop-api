package com.mangle.retailshopapp.user.model;

import lombok.Data;

@Data
public class RegisterUserVo {
    private String username;
    private String password;
    private String firstName;
    private String lastName;
    private String role;
}
