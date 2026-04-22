package com.yunnan.datacollect.repository;

import com.yunnan.datacollect.service.PlatformService;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<PlatformService.AuditLog, Long> {
}
