package com.auracademic.backend.component;

import com.auracademic.backend.model.User;
import com.auracademic.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;

@Component
public class AdminSeeder implements CommandLineRunner {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) throws Exception {
        if (!userRepository.existsByEmail("admin@smartex.com")) {
            User admin = new User();
            admin.setFullName("System Admin");
            admin.setEmail("admin@smartex.com");
            admin.setPassword(passwordEncoder.encode("admin@123"));
            admin.setRole("admin");
            admin.setProvider("local");
            admin.setEmailVerified(true);
            admin.setAccountLocked(false);
            admin.setFailedLoginAttempts(0);
            admin.setCreatedAt(LocalDateTime.now());
            userRepository.save(admin);
            System.out.println("====== SEEDED ADMIN ACCOUNT: admin@smartex.com ======");
        }
    }
}
