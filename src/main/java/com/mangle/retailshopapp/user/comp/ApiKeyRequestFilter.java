package com.mangle.retailshopapp.user.comp;

import java.io.IOException;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.mangle.retailshopapp.user.model.UserApiKey;
import com.mangle.retailshopapp.user.repo.UserRepository;
import com.mangle.retailshopapp.user.service.ApiKeyService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class ApiKeyRequestFilter extends OncePerRequestFilter {
    
    @Autowired
    private ApiKeyService apiKeyService;
    
    @Autowired
    private UserDetailsService userDetailsService;
    
    @Autowired
    private UserRepository userRepository;
    
    @Override
    protected void doFilterInternal(HttpServletRequest request, 
                                    HttpServletResponse response, 
                                    FilterChain chain) throws ServletException, IOException {
        String apiKey = request.getHeader("X-API-Key");
        
        if (apiKey != null && !apiKey.isEmpty()) {
            try {
                UserApiKey key = apiKeyService.validateApiKey(apiKey);
                
                // Set authentication context for API key
                if (SecurityContextHolder.getContext().getAuthentication() == null) {
                    // Load user details
                    UserDetails userDetails = userDetailsService.loadUserByUsername(
                        userRepository.findById((long) key.getUserId())
                            .orElseThrow(() -> new RuntimeException("User not found"))
                            .getUsername()
                    );
                    
                    // Create authentication token
                    UsernamePasswordAuthenticationToken authToken = 
                        new UsernamePasswordAuthenticationToken(
                            userDetails, null, userDetails.getAuthorities());
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    
                    // Set in security context
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                    
                    // Update last used date (async recommended for performance)
                    key.setLastUsedDate(LocalDateTime.now());
                    // Note: Consider async update for performance
                }
            } catch (Exception e) {
                // API key invalid, let JWT filter handle it
                SecurityContextHolder.clearContext();
            }
        }
        
        chain.doFilter(request, response);
    }
    
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean shouldNotFilter = path.equals("/authenticate") || 
               path.equals("/customer/register") ||
               path.equals("/customer/check-mobile") ||
               path.equals("/refresh-token");
        return shouldNotFilter;
    }
}
