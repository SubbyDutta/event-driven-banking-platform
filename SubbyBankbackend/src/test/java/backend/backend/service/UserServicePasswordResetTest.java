package backend.backend.service;

import backend.backend.configuration.SubbyProperties;
import backend.backend.events.PasswordResetRequested;
import backend.backend.messaging.OutboxEventPublisher;
import backend.backend.model.PasswordResetToken;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServicePasswordResetTest {

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
    void sendPasswordResetOtp_happyPath_persistsTokenAndPublishesEvent() {
        User user = new User();
        user.setId(42L);
        user.setEmail("alice@example.com");
        user.setUsername("alice");
        user.setFirstname("Alice");
        when(userRepo.findByEmail("alice@example.com")).thenReturn(Optional.of(user));

        userService.sendPasswordResetOtp("alice@example.com");

        ArgumentCaptor<PasswordResetToken> tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(resetTokenRepo).save(tokenCaptor.capture());
        PasswordResetToken saved = tokenCaptor.getValue();
        assertThat(saved.getEmail()).isEqualTo("alice@example.com");
        assertThat(saved.getOtp()).hasSize(6).matches("\\d{6}");
        assertThat(saved.getExpiryTime()).isNotNull();

        ArgumentCaptor<PasswordResetRequested> eventCaptor =
                ArgumentCaptor.forClass(PasswordResetRequested.class);
        verify(outboxPublisher).publish(eq("subby-notifications"), eventCaptor.capture());
        PasswordResetRequested event = eventCaptor.getValue();
        assertThat(event.getUserId()).isEqualTo("42");
        assertThat(event.getEmail()).isEqualTo("alice@example.com");
        assertThat(event.getFirstName()).isEqualTo("Alice");
        assertThat(event.getOtp()).isEqualTo(saved.getOtp());
        assertThat(event.getExpiresAt()).isNotBlank();
    }
}
