package com.auracademic.backend.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExamVersion {
    private String versionCode; // Ví dụ: 101, 102
    private List<Question> questions;
}
