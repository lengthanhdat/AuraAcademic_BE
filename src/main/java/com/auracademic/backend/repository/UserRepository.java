package com.auracademic.backend.repository;

import com.auracademic.backend.model.User;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends MongoRepository<User, String> {
    Optional<User> findByEmail(String email);
    Optional<User> findByEmailVerificationToken(String token);
    Optional<User> findByPasswordResetToken(String token);
    Optional<User> findByProviderAndProviderId(String provider, String providerId);
    boolean existsByEmail(String email);
    long countByRole(String role);
    long countByEmailVerified(boolean verified);
    long countByAccountLocked(boolean locked);
    List<User> findAllByOrderByCreatedAtDesc();
    List<User> findByRole(String role);
}
