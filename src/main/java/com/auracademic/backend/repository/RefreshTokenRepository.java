package com.auracademic.backend.repository;

import com.auracademic.backend.model.RefreshToken;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends MongoRepository<RefreshToken, String> {
    Optional<RefreshToken> findByToken(String token);
    List<RefreshToken> findByUserId(String userId);
    void deleteByUserId(String userId);
    void deleteByToken(String token);
    long countByUserId(String userId);
    boolean existsByUserIdAndSessionId(String userId, String sessionId);
}
