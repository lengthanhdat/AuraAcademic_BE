package com.auracademic.backend.repository;

import com.auracademic.backend.model.Material;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;

public interface MaterialRepository extends MongoRepository<Material, String> {

    List<Material> findByUploadedByOrderByCreatedAtDesc(String userId);

    List<Material> findByStatusOrderByCreatedAtDesc(String status);

    List<Material> findByUploaderRoleAndStatusOrderByCreatedAtDesc(String role, String status);

    @Query("{ 'status': 'published', $or: [ { 'title': { $regex: ?0, $options: 'i' } }, { 'description': { $regex: ?0, $options: 'i' } }, { 'tags': { $regex: ?0, $options: 'i' } } ] }")
    List<Material> searchPublished(String keyword);

    List<Material> findByStatusAndSubjectIgnoreCaseOrderByCreatedAtDesc(String status, String subject);

    List<Material> findByClassroomIdOrderByCreatedAtDesc(String classroomId);
}
