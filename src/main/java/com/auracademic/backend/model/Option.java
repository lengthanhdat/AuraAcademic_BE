package com.auracademic.backend.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Option {
    private String id;
    private String text;
    @JsonProperty("isCorrect")
    private boolean isCorrect;
}
