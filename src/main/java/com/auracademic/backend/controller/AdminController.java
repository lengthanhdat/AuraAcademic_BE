package com.auracademic.backend.controller;

import com.auracademic.backend.model.User;
import com.auracademic.backend.repository.ExamRepository;
import com.auracademic.backend.repository.ExamResultRepository;
import com.auracademic.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@CrossOrigin(origins = "http://localhost:3000")
public class AdminController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ExamRepository examRepository;

    @Autowired
    private ExamResultRepository resultRepository;

    /**
     * Lay tong quan he thong: so nguoi dung, so bai thi, so ket qua
     */
    @GetMapping("/stats")
    public ResponseEntity<?> getStats() {
        try {
            long totalUsers = userRepository.count();
            long totalTeachers = userRepository.countByRole("teacher");
            long totalStudents = userRepository.countByRole("student");
            long totalExams = examRepository.count();
            long publishedExams = examRepository.countByStatus("PUBLISHED");
            long totalResults = resultRepository.count();

            Map<String, Object> stats = new HashMap<>();
            stats.put("totalUsers", totalUsers);
            stats.put("totalTeachers", totalTeachers);
            stats.put("totalStudents", totalStudents);
            stats.put("totalExams", totalExams);
            stats.put("publishedExams", publishedExams);
            stats.put("totalResults", totalResults);

            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error fetching stats: " + e.getMessage());
        }
    }

    /**
     * Lay danh sach tat ca nguoi dung (giao vien + hoc sinh)
     */
    @GetMapping("/users")
    public ResponseEntity<?> getAllUsers() {
        try {
            List<User> users = userRepository.findAll();
            // Xoa mat khau truoc khi tra ve
            users.forEach(u -> u.setPassword("[HIDDEN]"));
            return ResponseEntity.ok(users);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error fetching users: " + e.getMessage());
        }
    }

    /**
     * Xoa nguoi dung theo ID
     */
    @DeleteMapping("/users/{id}")
    public ResponseEntity<?> deleteUser(@PathVariable String id) {
        try {
            userRepository.deleteById(id);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error deleting user: " + e.getMessage());
        }
    }

    /**
     * Cap nhat role cho nguoi dung
     */
    @PutMapping("/users/{id}/role")
    public ResponseEntity<?> updateUserRole(@PathVariable String id, @RequestBody Map<String, String> body) {
        try {
            String newRole = body.get("role");
            if (newRole == null || (!newRole.equals("student") && !newRole.equals("teacher") && !newRole.equals("admin"))) {
                return ResponseEntity.badRequest().body("Role khong hop le. Chi chap nhan: student, teacher, admin");
            }
            return userRepository.findById(id).map(user -> {
                user.setRole(newRole);
                userRepository.save(user);
                user.setPassword("[HIDDEN]");
                return ResponseEntity.ok(user);
            }).orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error updating role: " + e.getMessage());
        }
    }
}
