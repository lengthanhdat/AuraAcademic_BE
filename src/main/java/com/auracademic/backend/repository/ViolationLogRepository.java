package com.auracademic.backend.repository;

import com.auracademic.backend.model.ViolationLog;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;

public interface ViolationLogRepository extends MongoRepository<ViolationLog, String> {
    List<ViolationLog> findByExamCodeOrderByTimestampDesc(String examCode);
    List<ViolationLog> findByExamCodeAndStudentId(String examCode, String studentId);
}
