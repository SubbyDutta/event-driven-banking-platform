package backend.backend.service;

import backend.backend.configuration.SubbyProperties;
import backend.backend.events.PasswordChanged;
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

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServicePasswordChangedPublishTest {

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
    void resetPasswordWithOtp_happyPath_publishesPasswordChangedAfterTokenDelete() {
        PasswordResetToken token = new PasswordResetToken();
        token.setEmail("alice@example.com");
        token.setOtp("123456");
        token.setExpiryTime(LocalDateTime.now().plusMinutes(5));
        when(resetTokenRepo.findByEmailAndOtp("alice@example.com", "123456"))
                .thenReturn(Optional.of(token));

        User user = new User();
        user.setId(42L);
        user.setEmail("alice@example.com");
        user.setUsername("alice");
        user.setFirstname("Alice");
        when(userRepo.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("newpass")).thenReturn("encoded-newpass");

        userService.resetPasswordWithOtp("alice@example.com", "123456", "newpass");

        verify(userRepo).save(user);
        verify(resetTokenRepo).delete(token);

        ArgumentCaptor<PasswordChanged> eventCaptor = ArgumentCaptor.forClass(PasswordChanged.class);
        verify(outboxPublisher).publish(eq("subby-notifications"), eventCaptor.capture());
        PasswordChanged event = eventCaptor.getValue();
        assertThat(event.getUserId()).isEqualTo("42");
        assertThat(event.getEmail()).isEqualTo("alice@example.com");
        assertThat(event.getFirstName()).isEqualTo("Alice");
        assertThat(event.getOccurredAtIso()).isNotBlank();
        assertThat(event.eventType()).isEqualTo("PasswordChanged");

        var inOrder = inOrder(userRepo, resetTokenRepo, outboxPublisher);
        inOrder.verify(userRepo).save(user);
        inOrder.verify(resetTokenRepo).delete(token);
        inOrder.verify(outboxPublisher).publish(eq("subby-notifications"), org.mockito.ArgumentMatchers.any());
    }
}
