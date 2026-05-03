package backend.backend.service;

import backend.backend.configuration.AdminProperties;
import backend.backend.model.User;
import backend.backend.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AdminInitializer {
   private final AdminProperties adminProperties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private String username;
    private String password;
    @PostConstruct
    public void init()
    {
        username=adminProperties.getUsername();
        password= adminProperties.getPassword();
    }

    @Bean
    CommandLineRunner initAdmin() {
        return args -> {
            if (!userRepository.existsByUsername(username)) {
                User admin = new User();
                admin.setUsername(username);
                admin.setFirstname("ADMIN");
                admin.setLastname("ADMIN");

                admin.setPassword(passwordEncoder.encode(password));
                admin.setRole("ADMIN");
                admin.setEmail("adminsmartbank@gmail.com");
                admin.setMobile("8017798437");
                userRepository.save(admin);
                System.out.println("Admin user created: admin / admin123");
            }
        };
    }
}
