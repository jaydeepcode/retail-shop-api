package com.mangle.retailshopapp.user.model;

import java.util.Collection;

import org.springframework.security.core.GrantedAuthority;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserDetails {
    private String username; 
    private Collection<? extends GrantedAuthority> roles;
    private String firstName;
    private String lastName;
}
