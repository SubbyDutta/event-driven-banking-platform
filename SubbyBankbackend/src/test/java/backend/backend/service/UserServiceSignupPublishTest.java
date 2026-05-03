package backend.backend.service;

import backend.backend.configuration.SubbyProperties;
import backend.backend.events.UserSignedUp;
import backend.backend.messaging.OutboxEventPublisher;
import backend.backend.model.User;
import backend.backend.repository.BankAccountRepository;
import backend.backend.repository.PasswordResetTokenRepository;
import backend.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceSignupPublishTest {

    @Mock UserRepository userRepo;
    @Mock PasswordEncoder passwordEncoder;
    @Mock BankAccountRepository bankRepo;
    @Mock BuisnessLoggingService buisnessLoggingService;
    @Mock CachedLists cachedLists;
    @Mock PasswordResetTokenRepository resetTokenRepo;
    @Mock OutboxEventPublisher outboxPublisher;
    @Mock SubbyProperties subbyProperties;
    @Mock SubbyProperties.Topics topics;

    @InjectMocks UserService userService;

    @BeforeEach
    void wireTopics() {
        when(subbyProperties.topics()).thenReturn(topics);
        when(topics.notifications()).thenReturn("subby-notifications");
    }

    @Test
    void registerUser_persistsUserAndPublishesUserSignedUpEvent() {
        when(userRepo.findByUsername("alice")).thenReturn(Optional.empty());
        when(userRepo.findByEmail("alice@example.com")).thenReturn(Optional.empty());
        when(userRepo.findByMobile("9876543210")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("plain")).thenReturn("encoded");

        User user = new User();
        user.setUsername("alice");
        user.setEmail("alice@example.com");
        user.setMobile("9876543210");
        user.setFirstname("Alice");
        user.setLastname("L");
        user.setPassword("plain");
        user.setDob(LocalDate.of(1995, 1, 1));

        userService.registerUser(user);

        verify(userRepo).save(any(User.class));

        ArgumentCaptor<UserSignedUp> eventCaptor = ArgumentCaptor.forClass(UserSignedUp.class);
        verify(outboxPublisher).publish(eq("subby-notifications"), eventCaptor.capture());

        UserSignedUp event = eventCaptor.getValue();
        assertThat(event.getEmail()).isEqualTo("alice@example.com");
        assertThat(event.getFirstName()).isEqualTo("Alice");
        assertThat(event.getUsername()).isEqualTo("alice");
        assertThat(event.getSignedUpAt()).isNotBlank();
        assertThat(event.eventType()).isEqualTo("UserSignedUp");
    }
}
