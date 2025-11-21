package com.mangle.retailshopapp.customer.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Data;

@Data
@Entity 
@Table(name = "rs_cust_dtls")
public class CustomerDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int custId;

    @Column(name = "FIRST_NAME", nullable = false, length = 45)
    private String firstName;

    @Column(name = "LAST_NAME", nullable = false, length = 45)
    private String lastName;

    @Column(name = "CONTACT_NUM", nullable = false, length = 45)
    private String contactNum;

    @Column(name = "EMAIL", length = 100)
    private String email;

    @Column(name = "USER_ID")
    private Integer userId;

    @Column(name = "IS_ADMIN", nullable = false)
    private boolean isAdmin = false;

    @Column(name = "STATUS_CODE", length = 20)
    private String statusCode = "PENDING";

    @Transient
    private boolean active = false;

    @Column(name = "CRE_DTTM", nullable = false)
    private LocalDateTime creDttm;

    @Column(name = "UPD_DTTM")
    private LocalDateTime updDttm;

    // Helper method for backward compatibility
    public String getCustomerName() {
        return firstName + " " + lastName;
    }

    public void setCustomerName(String fullName) {
        if (fullName != null && fullName.contains(" ")) {
            String[] parts = fullName.split(" ", 2);
            this.firstName = parts[0];
            this.lastName = parts[1];
        } else {
            this.firstName = fullName;
            this.lastName = ".";
        }
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
        this.statusCode = active ? "ACTIVE" : "INACTIVE";
    }

    public void setStatusCode(String statusCode) {
        this.statusCode = statusCode;
        this.active = "ACTIVE".equalsIgnoreCase(statusCode);
    }

    @PostLoad
    private void syncActiveFromStatus() {
        this.active = "ACTIVE".equalsIgnoreCase(this.statusCode);
    }

}
