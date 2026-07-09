package com.auracademic.backend.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "system_settings")
public class SystemSetting {
    @Id
    private String id; // key name, e.g., "gemini.api.key"
    private String value;
    private String description;
    private Long lastUpdated;

    public SystemSetting() {}

    public SystemSetting(String id, String value, String description) {
        this.id = id;
        this.value = value;
        this.description = description;
        this.lastUpdated = System.currentTimeMillis();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Long getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(Long lastUpdated) { this.lastUpdated = lastUpdated; }
}
