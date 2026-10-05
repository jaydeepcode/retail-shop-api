package com.mangle.retailshopapp.notification.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "rc_pn_tokens")
@Data
public class PushNotificationToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    
    @Column(name = "user_id", nullable = false)
    private int userId;
    
    @Column(name = "device_token", nullable = false, unique = true, length = 255)
    private String deviceToken; // FCM token
    
    @Column(name = "platform", nullable = false, length = 20)
    private String platform; // iOS, Android, Web
    
    @Column(name = "created_date", nullable = false)
    private LocalDateTime createdDate = LocalDateTime.now();
    
    @Column(name = "last_updated", nullable = false)
    private LocalDateTime lastUpdated = LocalDateTime.now();
    
    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;
}
