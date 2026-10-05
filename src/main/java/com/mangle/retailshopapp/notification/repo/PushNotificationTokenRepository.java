package com.mangle.retailshopapp.notification.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.mangle.retailshopapp.notification.model.PushNotificationToken;

@Repository
public interface PushNotificationTokenRepository extends JpaRepository<PushNotificationToken, Integer> {
    
    Optional<PushNotificationToken> findByDeviceToken(String deviceToken);
    
    List<PushNotificationToken> findByUserIdAndIsActive(int userId, boolean isActive);
    
    List<PushNotificationToken> findByUserIdAndPlatformAndIsActive(int userId, String platform, boolean isActive);
}
