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
                        // Public endpoints - specific paths first
                        .requestMatchers("/authenticate", "/refresh-token").permitAll()
                        .requestMatchers("/customer/register", "/customer/check-mobile").permitAll()
                        
                        // Admin endpoints
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        
                        // Customer endpoints
                        .requestMatchers("/customer/**").hasAnyRole("ADMIN", "CUSTOMER")
                        .requestMatchers("/motor/status").hasAnyRole("ADMIN", "CUSTOMER")
                        .requestMatchers("/motor/pump/**").hasAnyRole("ADMIN", "CUSTOMER")

                        // The recharge counter (design-api.md §3.5, log §15.11).
                        //
                        // ⚠ ADMIN and USER, not §3.5's ADMIN and STAFF. ROLE_USER
                        // ALREADY IS the staff role: measured on the restore
                        // 2026-10-12, the only values in rc_user.roles are
                        // ROLE_ADMIN|ROLE_USER (6, the owner), ROLE_USER (2,
                        // working staff) and ROLE_CUSTOMER (2, water customers
                        // who start pumps from the mobile app). So this admits
                        // the owner and staff and excludes customers, and
                        // ROLE_STAFF is never created.
                        //
                        // Verified safe two ways: no user carries both
                        // ROLE_CUSTOMER and ROLE_USER, and
                        // CustomerRegistrationService.createCustomerUser assigns
                        // List.of("ROLE_CUSTOMER") and nothing else, so the public
                        // /customer/register path cannot mint an account that
                        // reaches the counter.
                        //
                        // ONE FRAGILITY, recorded rather than fixed: ROLE_USER is
                        // a generic name, so if anything ever grants it to a
                        // customer-facing account this API opens up. Nothing does
                        // today. Introducing ROLE_STAFF is the fix if that
                        // changes.
                        //
                        // It must stay ABOVE anyRequest(): the matchers are
                        // evaluated in order, so below the fallthrough it would
                        // never be reached and a ROLE_CUSTOMER token would read
                        // the entire counter API including the credit book.
                        //
                        // No @PreAuthorize anywhere in the module. §3.5 is
                        // explicit that requirement 9 — all staff see all
                        // transactions, any staff member may extend credit —
                        // means NO per-row authorisation. The control is the
                        // record: extendedBy, extendedAt and note taken from
                        // Principal.
                        .requestMatchers("/recharge/**")
                                .hasAnyAuthority(SecurityConstants.ADMIN_USER,
                                                 SecurityConstants.NORMAL_USER)

                        // ⚠ STILL OUTSTANDING, and S3 does not close it: 23 of the
                        // endpoints below fall through to authenticated-only, which
                        // is S0b's deny-by-default work (implementation-plan.md
                        // §0.3). S3 closes its own door and leaves the rest where
                        // it is.
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
