package com.auracademic.backend.controller;

import com.auracademic.backend.model.Classroom;
import com.auracademic.backend.model.User;
import com.auracademic.backend.repository.ClassroomRepository;
import com.auracademic.backend.repository.ExamRepository;
import com.auracademic.backend.repository.MaterialRepository;
import com.auracademic.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/classrooms")
public class ClassroomController {

    @Autowired
    private ClassroomRepository classroomRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ExamRepository examRepository;

    @Autowired
    private MaterialRepository materialRepository;

    // 1. Tạo lớp học mới (Teacher)
    @PostMapping
    @PreAuthorize("hasRole('TEACHER') or hasRole('ADMIN')")
    public ResponseEntity<?> createClassroom(@RequestBody Classroom req, Authentication auth) {
        User teacher = userRepository.findByEmail(auth.getName()).orElse(null);
        if (teacher == null) return ResponseEntity.status(401).body("Unauthorized");

        req.setTeacherId(teacher.getId());
        req.setTeacherName(teacher.getFullName() != null ? teacher.getFullName() : teacher.getEmail());
        req.setCreatedAt(LocalDateTime.now());
        
        // Tạo mã lớp 6 ký tự ngẫu nhiên duy nhất
        String code;
        do {
            code = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        } while (classroomRepository.findByCode(code).isPresent());
        req.setCode(code);
        
        req.setStudentIds(new ArrayList<>());
        req.setPendingStudentIds(new ArrayList<>());

        Classroom saved = classroomRepository.save(req);
        return ResponseEntity.ok(saved);
    }

    // 2. Lấy danh sách lớp học của giáo viên
    @GetMapping("/teacher")
    @PreAuthorize("hasRole('TEACHER') or hasRole('ADMIN')")
    public ResponseEntity<?> getTeacherClassrooms(Authentication auth) {
        User teacher = userRepository.findByEmail(auth.getName()).orElse(null);
        if (teacher == null) return ResponseEntity.status(401).body("Unauthorized");

        List<Classroom> classrooms = classroomRepository.findByTeacherIdOrderByCreatedAtDesc(teacher.getId());
        return ResponseEntity.ok(classrooms);
    }

    // 3. Lấy danh sách lớp học của học sinh
    @GetMapping("/student")
    @PreAuthorize("hasRole('STUDENT') or hasRole('ADMIN')")
    public ResponseEntity<?> getStudentClassrooms(Authentication auth) {
        User student = userRepository.findByEmail(auth.getName()).orElse(null);
        if (student == null) return ResponseEntity.status(401).body("Unauthorized");

        List<Classroom> classrooms = classroomRepository.findByStudentIdsContainingOrderByCreatedAtDesc(student.getId());
        return ResponseEntity.ok(classrooms);
    }

    // 4. Lấy chi tiết lớp học
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> getClassroomDetails(@PathVariable String id, Authentication auth) {
        Optional<Classroom> opt = classroomRepository.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        Classroom classroom = opt.get();

        User user = userRepository.findByEmail(auth.getName()).orElse(null);
        if (user == null) return ResponseEntity.status(401).body("Unauthorized");

        // Kiểm tra quyền: Chỉ cho phép Giáo viên tạo hoặc học sinh đã tham gia
        boolean isTeacher = user.getId().equals(classroom.getTeacherId());
        boolean isStudent = classroom.getStudentIds().contains(user.getId());
        boolean isAdmin = user.getRole().equalsIgnoreCase("ADMIN");

        if (!isTeacher && !isStudent && !isAdmin) {
            return ResponseEntity.status(403).body("Không có quyền truy cập lớp học này.");
        }

        Map<String, Object> response = new HashMap<>();
        response.put("classroom", classroom);
        response.put("exams", examRepository.findByClassroomId(id));
        response.put("materials", materialRepository.findByClassroomIdOrderByCreatedAtDesc(id));

        return ResponseEntity.ok(response);
    }

    // 5. Học sinh xin tham gia bằng mã code
    @PostMapping("/join")
    @PreAuthorize("hasRole('STUDENT') or hasRole('ADMIN')")
    public ResponseEntity<?> joinClassroom(@RequestBody Map<String, String> payload, Authentication auth) {
        String code = payload.get("code");
        if (code == null || code.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Mã lớp không hợp lệ");
        }

        User student = userRepository.findByEmail(auth.getName()).orElse(null);
        if (student == null) return ResponseEntity.status(401).body("Unauthorized");

        Optional<Classroom> opt = classroomRepository.findByCode(code.trim().toUpperCase());
        if (opt.isEmpty()) return ResponseEntity.status(404).body("Không tìm thấy lớp học với mã này");

        Classroom classroom = opt.get();
        if (classroom.getStudentIds().contains(student.getId())) {
            return ResponseEntity.badRequest().body("Bạn đã là thành viên chính thức của lớp này.");
        }
        if (classroom.getPendingStudentIds().contains(student.getId())) {
            return ResponseEntity.badRequest().body("Bạn đã gửi yêu cầu và đang chờ phê duyệt.");
        }

        classroom.getPendingStudentIds().add(student.getId());
        classroomRepository.save(classroom);
        return ResponseEntity.ok(Map.of("message", "Đã gửi yêu cầu tham gia lớp, vui lòng chờ giáo viên phê duyệt."));
    }

    // 6. Giáo viên thêm trực tiếp học sinh (Invite)
    @PostMapping("/{id}/invite")
    @PreAuthorize("hasRole('TEACHER') or hasRole('ADMIN')")
    public ResponseEntity<?> inviteStudent(@PathVariable String id, @RequestBody Map<String, String> payload, Authentication auth) {
        String email = payload.get("email");
        if (email == null) return ResponseEntity.badRequest().body("Email không hợp lệ");

        User teacher = userRepository.findByEmail(auth.getName()).orElse(null);
        Classroom classroom = classroomRepository.findById(id).orElse(null);

        if (teacher == null || classroom == null) return ResponseEntity.notFound().build();
        if (!classroom.getTeacherId().equals(teacher.getId())) return ResponseEntity.status(403).body("Unauthorized");

        Optional<User> studentOpt = userRepository.findByEmail(email);
        if (studentOpt.isEmpty()) return ResponseEntity.status(404).body("Không tìm thấy người dùng với email này");
        User student = studentOpt.get();

        if (classroom.getStudentIds().contains(student.getId())) {
            return ResponseEntity.badRequest().body("Học sinh này đã có trong lớp.");
        }

        // Tự động thêm vào danh sách chính thức
        classroom.getStudentIds().add(student.getId());
        classroom.getPendingStudentIds().remove(student.getId());
        classroomRepository.save(classroom);
        return ResponseEntity.ok(Map.of("message", "Thêm học sinh thành công."));
    }

    // 7. Giáo viên phê duyệt học sinh
    @PostMapping("/{id}/approve/{studentId}")
    @PreAuthorize("hasRole('TEACHER') or hasRole('ADMIN')")
    public ResponseEntity<?> approveStudent(@PathVariable String id, @PathVariable String studentId, Authentication auth) {
        User teacher = userRepository.findByEmail(auth.getName()).orElse(null);
        Classroom classroom = classroomRepository.findById(id).orElse(null);

        if (teacher == null || classroom == null) return ResponseEntity.notFound().build();
        if (!classroom.getTeacherId().equals(teacher.getId())) return ResponseEntity.status(403).body("Unauthorized");

        if (classroom.getPendingStudentIds().remove(studentId)) {
            if (!classroom.getStudentIds().contains(studentId)) {
                classroom.getStudentIds().add(studentId);
            }
            classroomRepository.save(classroom);
            return ResponseEntity.ok(Map.of("message", "Đã phê duyệt học sinh."));
        }
        return ResponseEntity.badRequest().body("Học sinh không nằm trong danh sách chờ.");
    }

    // 8. Giáo viên từ chối học sinh
    @PostMapping("/{id}/reject/{studentId}")
    @PreAuthorize("hasRole('TEACHER') or hasRole('ADMIN')")
    public ResponseEntity<?> rejectStudent(@PathVariable String id, @PathVariable String studentId, Authentication auth) {
        User teacher = userRepository.findByEmail(auth.getName()).orElse(null);
        Classroom classroom = classroomRepository.findById(id).orElse(null);

        if (teacher == null || classroom == null) return ResponseEntity.notFound().build();
        if (!classroom.getTeacherId().equals(teacher.getId())) return ResponseEntity.status(403).body("Unauthorized");

        if (classroom.getPendingStudentIds().remove(studentId)) {
            classroomRepository.save(classroom);
            return ResponseEntity.ok(Map.of("message", "Đã từ chối học sinh."));
        }
        return ResponseEntity.badRequest().body("Học sinh không nằm trong danh sách chờ.");
    }

    @Autowired
    private com.auracademic.backend.repository.ClassroomMessageRepository classroomMessageRepository;

    // 9. Lấy lịch sử chat nhóm của lớp (dùng để load lại khi mở tab Thảo luận)
    @GetMapping("/{id}/messages")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> getClassroomMessages(@PathVariable String id, Authentication auth) {
        Optional<Classroom> opt = classroomRepository.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        Classroom classroom = opt.get();

        User user = userRepository.findByEmail(auth.getName()).orElse(null);
        if (user == null) return ResponseEntity.status(401).body("Unauthorized");

        boolean isTeacher = user.getId().equals(classroom.getTeacherId());
        boolean isStudent = classroom.getStudentIds().contains(user.getId());
        boolean isAdmin = user.getRole().equalsIgnoreCase("ADMIN");
        if (!isTeacher && !isStudent && !isAdmin) {
            return ResponseEntity.status(403).body("Không có quyền truy cập.");
        }

        return ResponseEntity.ok(classroomMessageRepository.findByClassroomIdOrderByTimestampAsc(id));
    }
}
