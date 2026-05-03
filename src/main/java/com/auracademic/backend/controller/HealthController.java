package com.auracademic.backend.controller;

import com.auracademic.backend.model.User;
import com.auracademic.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:3000")
public class HealthController {

    @Autowired
    private UserRepository userRepository;

    @GetMapping("/health")
    public String checkHealth() {
        return "Backend is running!";
    }

    @GetMapping("/db-test")
    public List<User> testDb() {
        return userRepository.findAll();
    }
}
