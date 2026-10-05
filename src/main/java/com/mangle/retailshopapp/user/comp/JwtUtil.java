package com.mangle.retailshopapp.user.comp;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;

@Component
public class JwtUtil {
    @Value("${jwt.secret}")
    private String SECRET_KEY;
    
    @Value("${jwt.access-token.expiration}")
    private long ACCESS_TOKEN_EXPIRATION;
    
    @Value("${jwt.refresh-token.expiration}")
    private long REFRESH_TOKEN_EXPIRATION;
    
    @Value("${jwt.api-key.expiration}")
    private long API_KEY_EXPIRATION;

    // Generate access token (short-lived)
    public String generateAccessToken(UserDetails userDetails) {
        Map<String, Object> claims = new HashMap<>();
        return createToken(claims, userDetails.getUsername(), ACCESS_TOKEN_EXPIRATION);
    }

    // Generate access token with roles and waterPartyId
    public String generateAccessToken(UserDetails userDetails, Integer waterPartyId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("roles", extractRoles(userDetails));
        claims.put("waterPartyId", waterPartyId);
        claims.put("tokenType", "access");
        return createToken(claims, userDetails.getUsername(), ACCESS_TOKEN_EXPIRATION);
    }

    // Generate refresh token (long-lived)
    public String generateRefreshToken(UserDetails userDetails) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("tokenType", "refresh");
        return createToken(claims, userDetails.getUsername(), REFRESH_TOKEN_EXPIRATION);
    }

    // Generate API key token (long-lived)
    public String generateApiKey(UserDetails userDetails, Integer waterPartyId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("roles", extractRoles(userDetails));
        claims.put("waterPartyId", waterPartyId);
        claims.put("tokenType", "apiKey");
        return createToken(claims, userDetails.getUsername(), API_KEY_EXPIRATION);
    }

    private String createToken(Map<String, Object> claims, String username, long expirationTime) {
        return Jwts.builder().setClaims(claims).setSubject(username)
                .setIssuedAt(new Date(System.currentTimeMillis()))
                .setExpiration(new Date(System.currentTimeMillis() + expirationTime))
                .signWith(SignatureAlgorithm.HS256, SECRET_KEY).compact();
    }

    public Boolean validateToken(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        return (username.equals(userDetails.getUsername()) && !isTokenExpired(token));
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    private <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser().setSigningKey(SECRET_KEY).parseClaimsJws(token).getBody();
    }

    private Boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    // Extract roles from token
    @SuppressWarnings("unchecked")
    public List<String> extractRoles(String token) {
        Claims claims = extractAllClaims(token);
        return (List<String>) claims.get("roles");
    }

    // Extract waterPartyId from token
    public Integer extractWaterPartyId(String token) {
        Claims claims = extractAllClaims(token);
        return claims.get("waterPartyId", Integer.class);
    }

    // Extract token type from token
    public String extractTokenType(String token) {
        Claims claims = extractAllClaims(token);
        return (String) claims.get("tokenType");
    }

    // Helper method to extract roles from UserDetails
    private List<String> extractRoles(UserDetails userDetails) {
        return userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());
    }
}
