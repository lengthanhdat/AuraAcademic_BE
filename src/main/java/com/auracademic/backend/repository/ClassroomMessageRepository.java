package com.auracademic.backend.repository;

import com.auracademic.backend.model.ClassroomMessage;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClassroomMessageRepository extends MongoRepository<ClassroomMessage, String> {
    List<ClassroomMessage> findByClassroomIdOrderByTimestampAsc(String classroomId);
}
