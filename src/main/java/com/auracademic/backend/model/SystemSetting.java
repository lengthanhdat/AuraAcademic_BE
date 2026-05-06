package com.auracademic.backend.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "system_settings")
public class SystemSetting {
    @Id
    private String id;
    private String key;
    private Object value;
    private String type; // "boolean", "string", "number"

    public SystemSetting() {}

    public SystemSetting(String key, Object value, String type) {
        this.key = key;
        this.value = value;
        this.type = type;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public Object getValue() { return value; }
    public void setValue(Object value) { this.value = value; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
}
