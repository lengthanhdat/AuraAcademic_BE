package com.auracademic.backend.model;


import java.util.List;

public class ExamVersion {
    private String versionCode; // Ví dụ: 101, 102
    private List<Question> questions;

    public String getVersionCode() {
        return versionCode;
    }

    public void setVersionCode(String versionCode) {
        this.versionCode = versionCode;
    }

    public List<Question> getQuestions() {
        return questions;
    }

    public void setQuestions(List<Question> questions) {
        this.questions = questions;
    }
}
