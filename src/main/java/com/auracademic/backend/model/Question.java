package com.auracademic.backend.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Question {
    private String id;
    private String type; // "Trắc nghiệm", "Nhiều đáp án"
    private String text;
    private String imageUrl;
    private List<Option> options;
}
