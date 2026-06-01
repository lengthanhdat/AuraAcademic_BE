package com.auracademic.backend.controller;

import com.auracademic.backend.model.Classroom;
import com.auracademic.backend.model.Exam;
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

    @Autowired
    private com.auracademic.backend.repository.ClassroomPostRepository classroomPostRepository;

    // 1. Tạo lớp học mới (Teacher)
    @PostMapping
    @PreAuthorize("hasRole('TEACHER') or hasRole('ADMIN')")
    public ResponseEntity<?> createClassroom(@RequestBody Classroom req, Authentication auth) {
        User teacher = userRepository.findByEmail(auth.getName()).orElse(null);
        if (teacher == null) return ResponseEntity.status(401).body("Unauthorized");

        // Sandbox limit: STANDARD teachers can create at most 2 classrooms
        String verStatus = teacher.getVerificationStatus();
        boolean isStandardTeacher = "teacher".equalsIgnoreCase(teacher.getRole())
                && (verStatus == null || "STANDARD".equals(verStatus) || "PENDING".equals(verStatus) || "REJECTED".equals(verStatus));
        if (isStandardTeacher) {
            long ownedCount = classroomRepository.findByTeacherIdOrderByCreatedAtDesc(teacher.getId()).size();
            if (ownedCount >= 2) {
                return ResponseEntity.status(403).body(Map.of(
                    "error", "Tài khoản dùng thử chỉ được tạo tối đa 2 lớp học. Vui lòng xác thực tài khoản để mở khóa.",
                    "requiresVerification", true
                ));
            }
        }

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

        // Lấy thông tin chi tiết học sinh chính thức
        List<Map<String, String>> studentDetails = new ArrayList<>();
        if (classroom.getStudentIds() != null) {
            for (String sid : classroom.getStudentIds()) {
                userRepository.findById(sid).ifPresent(u -> {
                    Map<String, String> sInfo = new HashMap<>();
                    sInfo.put("id", u.getId());
                    sInfo.put("fullName", u.getFullName() != null ? u.getFullName() : u.getEmail());
                    sInfo.put("email", u.getEmail());
                    studentDetails.add(sInfo);
                });
            }
        }
        response.put("students", studentDetails);

        // Lấy thông tin chi tiết học sinh chờ duyệt
        List<Map<String, String>> pendingDetails = new ArrayList<>();
        if (classroom.getPendingStudentIds() != null) {
            for (String sid : classroom.getPendingStudentIds()) {
                userRepository.findById(sid).ifPresent(u -> {
                    Map<String, String> sInfo = new HashMap<>();
                    sInfo.put("id", u.getId());
                    sInfo.put("fullName", u.getFullName() != null ? u.getFullName() : u.getEmail());
                    sInfo.put("email", u.getEmail());
                    pendingDetails.add(sInfo);
                });
            }
        }
        response.put("pendingStudents", pendingDetails);

        // Lấy thông tin chi tiết học sinh đã bị vô hiệu hóa / đã rời lớp
        List<Map<String, String>> removedDetails = new ArrayList<>();
        if (classroom.getRemovedStudentIds() != null) {
            for (String sid : classroom.getRemovedStudentIds()) {
                userRepository.findById(sid).ifPresent(u -> {
                    Map<String, String> sInfo = new HashMap<>();
                    sInfo.put("id", u.getId());
                    sInfo.put("fullName", u.getFullName() != null ? u.getFullName() : u.getEmail());
                    sInfo.put("email", u.getEmail());
                    removedDetails.add(sInfo);
                });
            }
        }
        response.put("removedStudents", removedDetails);
        
        response.put("posts", classroomPostRepository.findByClassroomIdOrderByCreatedAtDesc(id));

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

    // Xóa/Vô hiệu hóa học sinh
    @PostMapping("/{id}/remove/{studentId}")
    @PreAuthorize("hasRole('TEACHER') or hasRole('ADMIN')")
    public ResponseEntity<?> removeStudent(@PathVariable String id, @PathVariable String studentId, Authentication auth) {
        User teacher = userRepository.findByEmail(auth.getName()).orElse(null);
        Classroom classroom = classroomRepository.findById(id).orElse(null);

        if (teacher == null || classroom == null) return ResponseEntity.notFound().build();
        if (!classroom.getTeacherId().equals(teacher.getId())) return ResponseEntity.status(403).body("Unauthorized");

        if (classroom.getStudentIds().remove(studentId)) {
            if (classroom.getRemovedStudentIds() == null) {
                classroom.setRemovedStudentIds(new ArrayList<>());
            }
            if (!classroom.getRemovedStudentIds().contains(studentId)) {
                classroom.getRemovedStudentIds().add(studentId);
            }
            classroomRepository.save(classroom);
            return ResponseEntity.ok(Map.of("message", "Đã xóa học sinh khỏi lớp."));
        }
        return ResponseEntity.badRequest().body("Học sinh không có trong lớp.");
    }

    // Lấy danh sách bài đăng (Bảng tin)
    @GetMapping("/{id}/posts")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> getClassroomPosts(@PathVariable String id, Authentication auth) {
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

        return ResponseEntity.ok(classroomPostRepository.findByClassroomIdOrderByCreatedAtDesc(id));
    }

    // Đăng thông báo mới
    @PostMapping("/{id}/posts")
    @PreAuthorize("hasRole('TEACHER') or hasRole('ADMIN')")
    public ResponseEntity<?> createClassroomPost(@PathVariable String id, @RequestBody Map<String, String> payload, Authentication auth) {
        String content = payload.get("content");
        if (content == null || content.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Nội dung không được để trống.");
        }

        Optional<Classroom> opt = classroomRepository.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        Classroom classroom = opt.get();

        User teacher = userRepository.findByEmail(auth.getName()).orElse(null);
        if (teacher == null) return ResponseEntity.status(401).body("Unauthorized");

        if (!classroom.getTeacherId().equals(teacher.getId()) && !teacher.getRole().equalsIgnoreCase("ADMIN")) {
            return ResponseEntity.status(403).body("Chỉ giáo viên mới được đăng thông báo.");
        }

        com.auracademic.backend.model.ClassroomPost post = new com.auracademic.backend.model.ClassroomPost();
        post.setClassroomId(id);
        post.setAuthorId(teacher.getId());
        post.setAuthorName(teacher.getFullName() != null ? teacher.getFullName() : teacher.getEmail());
        post.setContent(content.trim());
        
        return ResponseEntity.ok(classroomPostRepository.save(post));
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

    // 10. Lấy danh sách bài thi của một Lớp học
    @GetMapping("/{id}/exams")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> getClassroomExams(@PathVariable String id, Authentication auth) {
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

        List<Exam> exams = examRepository.findByClassroomId(id);
        return ResponseEntity.ok(exams);
    }

    // 11. Giao bài thi từ ngân hàng đề thi vào Lớp học (nhân bản đề thi)
    @PostMapping("/{id}/exams/link")
    @PreAuthorize("hasRole('TEACHER') or hasRole('ADMIN')")
    public ResponseEntity<?> linkExamFromBank(@PathVariable String id, @RequestBody Map<String, String> payload, Authentication auth) {
        String examId = payload.get("examId");
        if (examId == null || examId.isEmpty()) {
            return ResponseEntity.badRequest().body("Thiếu ID đề thi cần giao.");
        }

        Optional<Classroom> optClassroom = classroomRepository.findById(id);
        if (optClassroom.isEmpty()) return ResponseEntity.notFound().build();
        Classroom classroom = optClassroom.get();

        User teacher = userRepository.findByEmail(auth.getName()).orElse(null);
        if (teacher == null) return ResponseEntity.status(401).body("Unauthorized");

        if (!classroom.getTeacherId().equals(teacher.getId()) && !teacher.getRole().equalsIgnoreCase("ADMIN")) {
            return ResponseEntity.status(403).body("Không có quyền thực hiện hành động này.");
        }

        Optional<Exam> optExam = examRepository.findById(examId);
        if (optExam.isEmpty()) return ResponseEntity.status(404).body("Không tìm thấy đề thi gốc.");
        Exam sourceExam = optExam.get();

        // Tiến hành nhân bản đề thi (Clone)
        Exam clonedExam = new Exam();
        clonedExam.setTitle(sourceExam.getTitle());
        clonedExam.setDuration(sourceExam.getDuration());
        clonedExam.setShuffle(sourceExam.isShuffle());
        clonedExam.setAiProctoring(sourceExam.isAiProctoring());
        clonedExam.setTeacherId(teacher.getId());
        clonedExam.setTeacherName(teacher.getFullName() != null ? teacher.getFullName() : teacher.getEmail());
        clonedExam.setStatus("PUBLISHED"); // Đặt trạng thái ban đầu là PUBLISHED để học sinh có thể vào phòng chờ
        clonedExam.setDifficulty(sourceExam.getDifficulty());
        clonedExam.setVersions(sourceExam.getVersions());
        clonedExam.setExtractedImages(sourceExam.getExtractedImages());
        clonedExam.setPractice(false);
        clonedExam.setBankItem(false);
        clonedExam.setClassroomId(id);
        clonedExam.setFolderId(sourceExam.getFolderId());
        clonedExam.setGrade(sourceExam.getGrade());
        clonedExam.setSubject(sourceExam.getSubject());
        
        // Tạo mã phòng thi (accessCode) mới
        String accessCode;
        do {
            accessCode = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        } while (examRepository.findFirstByAccessCode(accessCode).isPresent());
        clonedExam.setAccessCode(accessCode);
        clonedExam.setCreatedAt(LocalDateTime.now());

        Exam savedExam = examRepository.save(clonedExam);
        return ResponseEntity.ok(savedExam);
    }

    // 12. Xóa bài kiểm tra khỏi Lớp học
    @DeleteMapping("/{id}/exams/{examId}")
    @PreAuthorize("hasRole('TEACHER') or hasRole('ADMIN')")
    public ResponseEntity<?> unlinkExam(@PathVariable String id, @PathVariable String examId, Authentication auth) {
        Optional<Classroom> optClassroom = classroomRepository.findById(id);
        if (optClassroom.isEmpty()) return ResponseEntity.notFound().build();
        Classroom classroom = optClassroom.get();

        User teacher = userRepository.findByEmail(auth.getName()).orElse(null);
        if (teacher == null) return ResponseEntity.status(401).body("Unauthorized");

        if (!classroom.getTeacherId().equals(teacher.getId()) && !teacher.getRole().equalsIgnoreCase("ADMIN")) {
            return ResponseEntity.status(403).body("Không có quyền thực hiện hành động này.");
        }

        Optional<Exam> optExam = examRepository.findById(examId);
        if (optExam.isEmpty()) return ResponseEntity.status(404).body("Không tìm thấy đề thi.");
        Exam exam = optExam.get();

        if (!id.equals(exam.getClassroomId())) {
            return ResponseEntity.badRequest().body("Đề thi này không thuộc lớp học hiện tại.");
        }

        examRepository.delete(exam);
        return ResponseEntity.ok(Map.of("message", "Đã xóa bài thi thành công!"));
    }
}
