package com.mangle.retailshopapp.user.model;

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

    @Column(name = "FIRST_NAME", nullable = false, length = 45)
    private String firstName;
    @Column(name = "LAST_NAME", nullable = false, length = 45)
    private String lastName;

    @Column(name = "ROLES", nullable = false, length = 80)
    private String roles;

     // Get roles as a List<String>
     public List<String> getRoles() {
        return Arrays.asList(this.roles.split("\\|"));
    }

    // Set roles from a List<String>
    public void setRoles(List<String> roles) {
        this.roles = String.join("|", roles);
    }
}
