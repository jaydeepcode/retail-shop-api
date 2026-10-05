package com.mangle.retailshopapp.user.model;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "rc_user") 
@Data
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(name = "USER_NAME", nullable = false, length = 45)
    private String username;
    @Column(name = "PASSWORD", nullable = false, length = 80)
    private String password;

    @Column(name = "ROLES", nullable = false, length = 80)
    private String roles;

    @Column(name = "ACCOUNT_STATUS", nullable = false, length = 20)
    private String accountStatus = "PENDING"; // PENDING, APPROVED, ACTIVE, SUSPENDED, DISABLED

    @Column(name = "APPROVED_BY")
    private Integer approvedBy; // Admin user ID who approved

    @Column(name = "APPROVED_DATE")
    private LocalDateTime approvedDate;

    @Column(name = "LAST_LOGIN_DATE")
    private LocalDateTime lastLoginDate;

    @Column(name = "CREATED_DATE")
    private LocalDateTime createdDate = LocalDateTime.now();

     // Get roles as a List<String>
     public List<String> getRoles() {
        return Arrays.asList(this.roles.split("\\|"));
    }

    // Set roles from a List<String>
    public void setRoles(List<String> roles) {
        this.roles = String.join("|", roles);
    }
}
