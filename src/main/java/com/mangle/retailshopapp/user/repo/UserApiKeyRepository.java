package com.mangle.retailshopapp.user.repo;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.mangle.retailshopapp.user.model.UserApiKey;

@Repository
public interface UserApiKeyRepository extends JpaRepository<UserApiKey, Integer> {
    
    Optional<UserApiKey> findByApiKeyAndIsActive(String apiKey, boolean isActive);
    
    Optional<UserApiKey> findByApiKey(String apiKey);
    
    @Modifying
    @Query("DELETE FROM UserApiKey u WHERE u.expiryDate < ?1 AND u.isActive = ?2")
    void deleteByExpiryDateBeforeAndIsActive(LocalDateTime now, boolean isActive);
    
    @Query("SELECT u FROM UserApiKey u WHERE u.userId = ?1 AND u.isActive = true")
    java.util.List<UserApiKey> findByUserIdAndIsActive(int userId, boolean isActive);
    
    Optional<UserApiKey> findByUserIdAndDeviceInfoAndIsActive(int userId, String deviceInfo, boolean isActive);
}
