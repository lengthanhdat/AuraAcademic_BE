package com.auracademic.backend;

import com.auracademic.backend.model.User;
import com.auracademic.backend.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.time.LocalDateTime;

@SpringBootApplication
@EnableAsync   // Kích hoạt xử lý bất đồng bộ với @Async
@EnableRetry   // Kích hoạt retry tự động với @Retryable
@EnableScheduling  // Kích hoạt tác vụ định kỳ với @Scheduled
public class AuraBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(AuraBackendApplication.class, args);
	}

	@Bean
	public CommandLineRunner seedAdmin(UserRepository userRepository, PasswordEncoder passwordEncoder) {
		return args -> {
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
		};
	}
}

