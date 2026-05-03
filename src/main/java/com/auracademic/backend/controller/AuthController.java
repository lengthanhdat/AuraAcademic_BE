package com.auracademic.backend.controller;

import com.auracademic.backend.dto.LoginRequest;
import com.auracademic.backend.dto.RegisterRequest;
import com.auracademic.backend.model.User;
import com.auracademic.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "http://localhost:3000")
public class AuthController {

    @Autowired
    private UserRepository userRepository;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest request) {
        if (userRepository.findByEmail(request.getEmail()) != null) {
            Map<String, String> response = new HashMap<>();
            response.put("error", "Email đã tồn tại");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }

        User newUser = new User();
        newUser.setFullName(request.getFullName());
        newUser.setEmail(request.getEmail());
        newUser.setPassword(passwordEncoder.encode(request.getPassword())); // Hash mat khau truoc khi luu
        newUser.setRole(request.getRole() != null ? request.getRole() : "student");
        
        userRepository.save(newUser);

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Đăng ký thành công");
        response.put("user", newUser);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail());
        
        // Ho tro migration: neu mat khau cu la plaintext, tu dong bam lai
        boolean passwordMatches = false;
        if (user != null) {
            String stored = user.getPassword();
            if (stored != null && stored.startsWith("$2a$")) {
                // Mat khau da duoc bam boi BCrypt
                passwordMatches = passwordEncoder.matches(request.getPassword(), stored);
            } else {
                // Mat khau cu chua duoc bam (plaintext) - so sanh truc tiep va tu dong bam lai
                passwordMatches = request.getPassword().equals(stored);
                if (passwordMatches) {
                    user.setPassword(passwordEncoder.encode(request.getPassword()));
                    userRepository.save(user);
                }
            }
        }
        if (!passwordMatches) {
            Map<String, String> response = new HashMap<>();
            response.put("error", "Email hoac mat khau khong chinh xac");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Dang nhap thanh cong");
        response.put("user", user);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/profile/{id}")
    public ResponseEntity<?> updateProfile(@PathVariable String id, @RequestBody Map<String, String> body) {
        return userRepository.findById(id).map(user -> {
            if (body.containsKey("fullName") && !body.get("fullName").isBlank()) {
                user.setFullName(body.get("fullName"));
            }
            if (body.containsKey("studentId")) {
                user.setStudentId(body.get("studentId"));
            }
            if (body.containsKey("phoneNumber")) {
                user.setPhoneNumber(body.get("phoneNumber"));
            }
            if (body.containsKey("birthDate")) {
                user.setBirthDate(body.get("birthDate"));
            }
            if (body.containsKey("gender")) {
                user.setGender(body.get("gender"));
            }
            if (body.containsKey("title")) {
                user.setTitle(body.get("title"));
            }
            if (body.containsKey("department")) {
                user.setDepartment(body.get("department"));
            }
            if (body.containsKey("workplace")) {
                user.setWorkplace(body.get("workplace"));
            }
            if (body.containsKey("schedule")) {
                user.setSchedule(body.get("schedule"));
            }
            userRepository.save(user);
            user.setPassword("[HIDDEN]");
            return ResponseEntity.ok(user);
        }).orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/change-password/{id}")
    public ResponseEntity<?> changePassword(@PathVariable String id, @RequestBody Map<String, String> body) {
        String currentPassword = body.get("currentPassword");
        String newPassword = body.get("newPassword");
        if (currentPassword == null || newPassword == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Thieu du lieu"));
        }
        return userRepository.findById(id).map(user -> {
            if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
                return ResponseEntity.status(401).body(Map.of("error", "Mat khau hien tai khong dung"));
            }
            user.setPassword(passwordEncoder.encode(newPassword));
            userRepository.save(user);
            return ResponseEntity.ok(Map.of("message", "Doi mat khau thanh cong"));
        }).orElse(ResponseEntity.notFound().build());
    }
}
