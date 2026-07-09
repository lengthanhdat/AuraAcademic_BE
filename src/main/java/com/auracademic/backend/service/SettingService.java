package com.auracademic.backend.service;

import com.auracademic.backend.model.SystemSetting;
import com.auracademic.backend.repository.SystemSettingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Service
public class SettingService {

    public static final String REQUIRE_2FA = "require_2fa";
    public static final String LOCK_AFTER_5_FAILS = "lock_after_5_fails";
    public static final String PREVENT_CONCURRENT_LOGIN = "prevent_concurrent_login";
    public static final String ENABLE_AUDIT_LOG = "enable_audit_log";
    public static final String REQUIRE_EMAIL_VERIFY = "require_email_verify";
    public static final String ALERT_SUSPICIOUS_LOGIN = "alert_suspicious_login";
    public static final String ENABLE_AI_PROCTOR = "enable_ai_proctor";
    public static final String AUTO_DETECT_CHEAT = "auto_detect_cheat";
    public static final String MAINTENANCE_MODE = "maintenance_mode";
    public static final String CHAT_AI_ENABLED = "chat_ai_enabled";

    private static final Map<String, String> DEFAULT_SETTINGS = new LinkedHashMap<>();
    private static final Set<String> BOOLEAN_SETTINGS = Set.of(
            REQUIRE_2FA,
            LOCK_AFTER_5_FAILS,
            PREVENT_CONCURRENT_LOGIN,
            ENABLE_AUDIT_LOG,
            REQUIRE_EMAIL_VERIFY,
            ALERT_SUSPICIOUS_LOGIN,
            ENABLE_AI_PROCTOR,
            AUTO_DETECT_CHEAT,
            MAINTENANCE_MODE,
            CHAT_AI_ENABLED
    );

    static {
        DEFAULT_SETTINGS.put("system_name", "AuraAcademic");
        DEFAULT_SETTINGS.put("system_version", "1.0.0");
        DEFAULT_SETTINGS.put("system_desc", "AI-powered examination management platform");
        DEFAULT_SETTINGS.put(REQUIRE_2FA, "false");
        DEFAULT_SETTINGS.put(LOCK_AFTER_5_FAILS, "true");
        DEFAULT_SETTINGS.put(PREVENT_CONCURRENT_LOGIN, "false");
        DEFAULT_SETTINGS.put(ENABLE_AUDIT_LOG, "true");
        DEFAULT_SETTINGS.put(REQUIRE_EMAIL_VERIFY, "true");
        DEFAULT_SETTINGS.put(ALERT_SUSPICIOUS_LOGIN, "false");
        DEFAULT_SETTINGS.put("gemini.api.key", "");
        DEFAULT_SETTINGS.put("groq.api.key", "");
        DEFAULT_SETTINGS.put(ENABLE_AI_PROCTOR, "true");
        DEFAULT_SETTINGS.put(AUTO_DETECT_CHEAT, "true");
        DEFAULT_SETTINGS.put(MAINTENANCE_MODE, "false");
        DEFAULT_SETTINGS.put(CHAT_AI_ENABLED, "true");
    }

    @Autowired
    private SystemSettingRepository repository;

    public String getSetting(String key, String defaultValue) {
        String fallback = defaultValue != null ? defaultValue : DEFAULT_SETTINGS.get(key);
        return repository.findById(key)
                .map(SystemSetting::getValue)
                .filter(val -> val != null && !val.trim().isEmpty())
                .orElse(fallback);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String val = getSetting(key, null);
        if (val == null) return defaultValue;
        return "true".equalsIgnoreCase(val)
                || "1".equals(val)
                || "yes".equalsIgnoreCase(val)
                || "on".equalsIgnoreCase(val);
    }

    public Map<String, String> getAllSettings() {
        Map<String, String> settings = new LinkedHashMap<>(DEFAULT_SETTINGS);
        for (SystemSetting setting : repository.findAll()) {
            settings.put(setting.getId(), setting.getValue() != null ? setting.getValue() : "");
        }
        return settings;
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
                saveSetting(entry.getKey(), normalizeValue(entry.getKey(), entry.getValue()), "System Updated");
            }
        }
    }

    private String normalizeValue(String key, Object value) {
        if (BOOLEAN_SETTINGS.contains(key)) {
            if (value instanceof Boolean bool) {
                return Boolean.toString(bool);
            }
            String text = String.valueOf(value).trim();
            return Boolean.toString(
                    "true".equalsIgnoreCase(text)
                            || "1".equals(text)
                            || "yes".equalsIgnoreCase(text)
                            || "on".equalsIgnoreCase(text)
            );
        }
        return String.valueOf(value);
    }
}
