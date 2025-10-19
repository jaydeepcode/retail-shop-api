package com.mangle.retailshopapp.config;

import java.util.Arrays;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.mangle.retailshopapp.user.comp.ApiKeyRequestFilter;
import com.mangle.retailshopapp.user.comp.JwtRequestFilter;
import com.mangle.retailshopapp.user.service.RetailAppUserService;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    @Autowired
    private RetailAppUserService retailAppUserService;

    @Autowired
    private JwtRequestFilter jwtRequestFilter;

    @Autowired
    private ApiKeyRequestFilter apiKeyRequestFilter;

    @Autowired
    private PasswordEncoder passwordEncoder;
    
    @Value("${app.security.cors.allowed-origins}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity httpSecurity) throws Exception {

        httpSecurity
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests((authorizeRequests) -> authorizeRequests
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/*", "/index.html", "/static/**", "/assets/**").permitAll()
                        // Public endpoints
                        .requestMatchers("/api/authenticate", "/api/customer/register", "/api/refresh-token").permitAll()
                        
                        // Admin endpoints
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        
                        // Customer endpoints
                        .requestMatchers("/api/customer/**", "/api/motor/status").hasAnyRole("ADMIN", "CUSTOMER")
                        .requestMatchers("/api/motor/pump/**").hasAnyRole("ADMIN", "CUSTOMER")
                        
                        .anyRequest().authenticated())
                .sessionManagement((sessionManagement) -> sessionManagement
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        // Add filters in correct order: API Key first, then JWT
        httpSecurity.addFilterBefore(apiKeyRequestFilter, UsernamePasswordAuthenticationFilter.class);
        httpSecurity.addFilterBefore(jwtRequestFilter, UsernamePasswordAuthenticationFilter.class);

        return httpSecurity.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(HttpSecurity httpSecurity) throws Exception {
        AuthenticationManagerBuilder authenticationManagerBuilder = httpSecurity
                .getSharedObject(AuthenticationManagerBuilder.class);
        authenticationManagerBuilder.userDetailsService(retailAppUserService).passwordEncoder(passwordEncoder);
        return authenticationManagerBuilder.build();
    }

    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(Arrays.asList(allowedOrigins.split(",")));
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(Arrays.asList("*"));
        config.setExposedHeaders(Arrays.asList("Authorization", "X-API-Key"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

}
