package com.mangle.retailshopapp.user.controller;

import java.util.Collection;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mangle.retailshopapp.user.comp.JwtUtil;
import com.mangle.retailshopapp.user.model.User;
import com.mangle.retailshopapp.user.model.UserDetails;
import com.mangle.retailshopapp.user.repo.UserRepository;

@RestController
@RequestMapping("/api")
public class UserController {

    @Autowired
    UserRepository userRepository;

    @Autowired
    private JwtUtil jwtTokenUtil;

    @Autowired
    private UserDetailsService myUserDetailsService;

    @GetMapping("/user/details")
    public UserDetails getUserDetails() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();
        Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
        User userInformation = userRepository.findByUsername(username);

        return new UserDetails(username, authorities, userInformation.getFirstName(), userInformation.getLastName());
    }

    @PostMapping("/refresh-token")
    public ResponseEntity<?> refreshToken(@RequestHeader("Refresh-Token") String refreshToken) {
        String username = jwtTokenUtil.extractUsername(refreshToken);
        org.springframework.security.core.userdetails.UserDetails userDetails = myUserDetailsService
                .loadUserByUsername(username);

        if (jwtTokenUtil.validateToken(refreshToken, userDetails)) {
            // Generate a new access token
            String newAccessToken = jwtTokenUtil.generateAccessToken(userDetails);

            // Optionally, generate a new refresh token (if you want to rotate refresh
            // tokens)
            String newRefreshToken = jwtTokenUtil.generateRefreshToken(userDetails);

            return ResponseEntity.ok(new AuthenticationResponse(newAccessToken, newRefreshToken));
        } else {
            throw new RuntimeException("Invalid refresh token");
        }

    }
}
