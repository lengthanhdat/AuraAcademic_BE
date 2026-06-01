package com.auracademic.backend.repository;

import com.auracademic.backend.model.ClassroomPost;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository

public interface ClassroomPostRepository extends MongoRepository<ClassroomPost, String> {
    List<ClassroomPost> findByClassroomIdOrderByCreatedAtDesc(String classroomId);
}
