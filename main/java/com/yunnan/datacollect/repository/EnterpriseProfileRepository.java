package com.yunnan.datacollect.repository;

import com.yunnan.datacollect.service.PlatformService;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EnterpriseProfileRepository extends JpaRepository<PlatformService.EnterpriseProfile, Long> {
}
