package com.auracademic.backend.service;

import com.auracademic.backend.model.SystemSetting;
import com.auracademic.backend.repository.SystemSettingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SettingService {

    @Autowired
    private SystemSettingRepository repository;

    public String getSetting(String key, String defaultValue) {
        return repository.findById(key)
                .map(SystemSetting::getValue)
                .filter(val -> val != null && !val.trim().isEmpty())
                .orElse(defaultValue);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String val = getSetting(key, null);
        if (val == null) return defaultValue;
        return Boolean.parseBoolean(val);
    }

    public Map<String, String> getAllSettings() {
        return repository.findAll().stream()
                .collect(Collectors.toMap(SystemSetting::getId, SystemSetting::getValue, (v1, v2) -> v1));
    }

    public void saveSetting(String key, String value, String description) {
        SystemSetting setting = repository.findById(key)
                .orElse(new SystemSetting(key, value, description));
        
        setting.setValue(value);
        setting.setLastUpdated(System.currentTimeMillis());
        repository.save(setting);
    }

    public void updateSettings(Map<String, Object> settings) {
        for (Map.Entry<String, Object> entry : settings.entrySet()) {
            if (entry.getValue() != null) {
                saveSetting(entry.getKey(), String.valueOf(entry.getValue()), "System Updated");
            }
        }
    }
}
