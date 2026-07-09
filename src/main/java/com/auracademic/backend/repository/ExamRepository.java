package com.auracademic.backend.repository;

import com.auracademic.backend.model.Exam;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;
import java.util.Optional;

public interface ExamRepository extends MongoRepository<Exam, String> {
    List<Exam> findByTeacherId(String teacherId);
    Optional<Exam> findFirstByAccessCode(String accessCode);
    long countByStatus(String status);
    List<Exam> findByIsPractice(boolean isPractice);
    List<Exam> findByFolderId(String folderId);
    List<Exam> findByClassroomId(String classroomId);
}
