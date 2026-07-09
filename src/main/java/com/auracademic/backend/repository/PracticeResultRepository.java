package com.auracademic.backend.repository;

import com.auracademic.backend.model.PracticeResult;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;
import java.util.Optional;

public interface PracticeResultRepository extends MongoRepository<PracticeResult, String> {
    List<PracticeResult> findByStudentId(String studentId);
    List<PracticeResult> findByExamId(String examId);
    long countByExamId(String examId);
}
