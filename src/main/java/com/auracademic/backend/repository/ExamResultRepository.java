package com.auracademic.backend.repository;

import com.auracademic.backend.model.ExamResult;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;

public interface ExamResultRepository extends MongoRepository<ExamResult, String> {
    List<ExamResult> findByStudentId(String studentId);
    List<ExamResult> findByExamId(String examId);
    boolean existsByStudentIdAndExamId(String studentId, String examId);
    long countByExamId(String examId);
}
