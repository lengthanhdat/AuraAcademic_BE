package com.auracademic.backend.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class Option {
    private String id;
    private String text;
    @JsonProperty("isCorrect")
    private boolean isCorrect;

    public Option() {}
    public Option(String id, String text, boolean isCorrect) {
        this.id = id; this.text = text; this.isCorrect = isCorrect;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public boolean isCorrect() { return isCorrect; }
    public void setCorrect(boolean correct) { isCorrect = correct; }

    public boolean isIsCorrect() {
        return isCorrect;
    }

    public void setIsCorrect(boolean isCorrect) {
        this.isCorrect = isCorrect;
    }
}
