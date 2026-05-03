package com.auracademic.backend.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "users")
public class User {
    @Id
    private String id;
    private String studentId;
    private String fullName;
    private String email;
    private String phoneNumber;
    private String birthDate;
    private String gender;
    private String title;
    private String department;
    private String workplace;
    private String schedule;
    private String password;
    private String role; // "student", "teacher", "admin"
}
