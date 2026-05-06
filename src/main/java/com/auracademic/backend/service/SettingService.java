package com.auracademic.backend.service;

import com.auracademic.backend.model.SystemSetting;
import com.auracademic.backend.repository.SystemSettingRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SettingService {
    private final SystemSettingRepository repository;

    public SettingService(SystemSettingRepository repository) {
        this.repository = repository;
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return repository.findByKey(key)
                .map(s -> Boolean.parseBoolean(s.getValue().toString()))
                .orElse(defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        return repository.findByKey(key)
                .map(s -> {
                    try {
                        return Integer.parseInt(s.getValue().toString());
                    } catch (NumberFormatException e) {
                        return defaultValue;
                    }
                })
                .orElse(defaultValue);
    }

    public String getString(String key, String defaultValue) {
        return repository.findByKey(key)
                .map(s -> s.getValue().toString())
                .orElse(defaultValue);
    }

    public void updateSettings(Map<String, Object> settings) {
        for (Map.Entry<String, Object> entry : settings.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            String type = value instanceof Boolean ? "boolean" : (value instanceof Number ? "number" : "string");
            
            SystemSetting s = repository.findByKey(key).orElse(new SystemSetting(key, value, type));
            s.setValue(value);
            s.setType(type);
            repository.save(s);
        }
    }

    public Map<String, Object> getAllSettings() {
        List<SystemSetting> list = repository.findAll();
        Map<String, Object> map = new HashMap<>();
        for (SystemSetting s : list) {
            Object val = s.getValue();
            if ("boolean".equals(s.getType()) && val instanceof String) {
                val = Boolean.parseBoolean((String) val);
            } else if ("number".equals(s.getType()) && val instanceof String) {
                try { val = Integer.parseInt((String) val); } catch (Exception ignored) {}
            }
            map.put(s.getKey(), val);
        }
        return map;
    }
}
