package com.mangle.retailshopapp.customer.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Entity 
@Table(name = "rs_cust_dtls")
public class CustomerDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int custId;

    @Column(name = "CUST_NAME", nullable = false, length = 45)
    private String customerName;

    @Column(name = "CONTACT_NUM", nullable = false, length = 45)
    private String contactNum;

    @Column(name = "CRE_DTTM", nullable = false)
    private LocalDateTime creDttm;

}
