package com.mangle.retailshopapp.recharge.repo;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.mangle.retailshopapp.recharge.model.RcSetting;

@Repository
public interface RcSettingRepository extends JpaRepository<RcSetting, String> {

    Optional<RcSetting> findBySettingKey(String settingKey);
}
