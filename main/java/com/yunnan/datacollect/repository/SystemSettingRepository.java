package com.yunnan.datacollect.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.yunnan.datacollect.service.PlatformService;

public interface SystemSettingRepository extends JpaRepository<PlatformService.SystemSetting, String> {
}