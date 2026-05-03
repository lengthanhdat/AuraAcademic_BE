package com.auracademic.backend.repository;

import com.auracademic.backend.model.AiJob;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository cho collection ai_jobs trong MongoDB.
 * Spring Data tự động generate các method CRUD cơ bản.
 */
@Repository
public interface AiJobRepository extends MongoRepository<AiJob, String> {
}
