package com.auracademic.backend.service;

import com.auracademic.backend.model.AuditLog;
import com.auracademic.backend.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final SettingService settingService;

    public AuditLogService(AuditLogRepository auditLogRepository, SettingService settingService) {
        this.auditLogRepository = auditLogRepository;
        this.settingService = settingService;
    }


    @Async
    public void log(String userId, String email, String event, String ipAddress, String userAgent, boolean success, String details) {
        if (!settingService.getBoolean(SettingService.ENABLE_AUDIT_LOG, true)) {
            return;
        }
        AuditLog entry = new AuditLog();
        entry.setUserId(userId);
        entry.setEmail(email);
        entry.setEvent(event);
        entry.setIpAddress(ipAddress);
        entry.setUserAgent(userAgent);
        entry.setSuccess(success);
        entry.setDetails(details);
        auditLogRepository.save(entry);
    }

    public List<AuditLog> getUserLogs(String userId) {
        return auditLogRepository.findByUserIdOrderByTimestampDesc(userId);
    }

}
