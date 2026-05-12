package com.auracademic.backend.controller;

import com.auracademic.backend.model.User;
import com.auracademic.backend.repository.UserRepository;
import com.auracademic.backend.service.SettingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")

public class HealthController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SettingService settingService;

    @GetMapping("/health")
    public String checkHealth() {
        return "Backend is running!";
    }

    @GetMapping("/health/status")
    public Map<String, Object> checkStatus() {
        boolean maintenanceMode = settingService.getBoolean(SettingService.MAINTENANCE_MODE, false);
        return Map.of(
                "ok", !maintenanceMode,
                "maintenanceMode", maintenanceMode,
                "timestamp", System.currentTimeMillis()
        );
    }

    @GetMapping("/db-test")
    public List<User> testDb() {
        return userRepository.findAll();
    }
}
