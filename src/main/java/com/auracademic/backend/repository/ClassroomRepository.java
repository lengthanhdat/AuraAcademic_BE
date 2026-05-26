package com.auracademic.backend.repository;

import com.auracademic.backend.model.Classroom;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClassroomRepository extends MongoRepository<Classroom, String> {
    Optional<Classroom> findByCode(String code);
    List<Classroom> findByTeacherIdOrderByCreatedAtDesc(String teacherId);
    List<Classroom> findByStudentIdsContainingOrderByCreatedAtDesc(String studentId);
}
