package backend.backend.service;

import backend.backend.Dtos.UserResponseDto;
import backend.backend.Exception.ResourceNotFoundException;
import backend.backend.Exception.UnauthorizedException;
import backend.backend.configuration.SubbyProperties;
import backend.backend.events.PasswordChanged;
import backend.backend.events.PasswordResetRequested;
import backend.backend.events.UserSignedUp;
import backend.backend.messaging.OutboxEventPublisher;
import backend.backend.model.PasswordResetToken;
import backend.backend.model.User;
import backend.backend.repository.BankAccountRepository;
import backend.backend.repository.PasswordResetTokenRepository;
import backend.backend.repository.UserRepository;
import backend.backend.requests_response.*;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Service
public class UserService {

    private static final SecureRandom OTP_RANDOM = new SecureRandom();
    private static final Duration OTP_TTL = Duration.ofMinutes(15);

    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final BankAccountRepository bankRepo;
    private final BuisnessLoggingService buisnessLoggingService;
    private final CachedLists cachedLists;
    private final PasswordResetTokenRepository resetTokenRepo;
    private final OutboxEventPublisher outboxPublisher;
    private final SubbyProperties subbyProperties;

    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "banking:users:list", allEntries = true)
    })
    public void registerUser(User user) {

        if (userRepo.findByUsername(user.getUsername()).isPresent()) {
            throw new UnauthorizedException("Username already exists!");
        }

        if (userRepo.findByEmail(user.getEmail()).isPresent()) {
            throw new UnauthorizedException("Email already registered!");
        }

        if (userRepo.findByMobile(user.getMobile()).isPresent()) {
            throw new UnauthorizedException("Mobile number already exists!");
        }

        if (user.getDob() == null) {
            throw new UnauthorizedException("Date of birth (dob) is required at signup");
        }

        user.setPassword(passwordEncoder.encode(user.getPassword()));
        user.setRole("USER");

        user.setCreditScore(0);
        user.setHasLoan(false);
        user.setRemaining(0);
        user.setLoanamount(0);
        user.setDueDate(null);
        userRepo.save(user);

        outboxPublisher.publish(
                subbyProperties.topics().notifications(),
                UserSignedUp.forUser(
                        String.valueOf(user.getId()),
                        user.getEmail(),
                        firstNonBlank(user.getFirstname(), user.getUsername()),
                        user.getUsername(),
                        Instant.now()));

        buisnessLoggingService.log(
                "REGISTERED",
                user.getUsername(),
                "REGISTERED WITH EMAIL " + user.getEmail() +
                        " MOBILE " + user.getMobile()
        );
    }

    public PagedResponse<UserResponseDto> getAllUsers(int page, int size) {
        if (page < 0) page = 0;
        if (size <= 0) size = 20;
        if (size > 100) size = 100;

        List<UserResponseDto> content =
                cachedLists.getAllUserCached(page,size);

        long totalElements = userRepo.count();
        int totalPages = (int) Math.ceil((double) totalElements / size);

        return new PagedResponse<>(
                content,
                page,
                size,
                totalElements,
                totalPages,
                page + 1 >= totalPages
        );

    }

    @Cacheable(value = "banking:user:byId", key = "#id", sync = true)
    public UserResponseDto getUserById(Long id) {
        System.out.println("DB HIT -> getUserById : " + id);
        User use = userRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return toDto(use);
    }

    @Caching(evict = {
            @CacheEvict(value = "banking:user:byId", key = "#id"),
            @CacheEvict(value = "banking:credit:score", allEntries = true),
            @CacheEvict(value = "banking:users:list", allEntries = true)
    })
    @Transactional
    public UserResponseDto updateUser(Long id, User updated) {
        User user = userRepo.findById(id).orElseThrow(() ->
                new ResourceNotFoundException("User not found with ID: " + id)
        );
        System.out.println("DB HIT UPDATE USER: " + id);

        user.setEmail(updated.getEmail());
        user.setMobile(updated.getMobile());
        user.setFirstname(updated.getFirstname());
        user.setLastname(updated.getLastname());
        user.setCreditScore(updated.getCreditScore());
        user.setRole(updated.getRole());
        user.setHasLoan(updated.isHasLoan());
        user.setRemaining(updated.getRemaining());
        user.setLoanamount(updated.getLoanamount());
        User savedUser = userRepo.save(user);

        buisnessLoggingService.log("USER UPDATED", user.getUsername(),
                "UPDATED AT " + user.getUpdatedAt());

        bankRepo.findByUserId(id).ifPresent(account -> {
            account.setUser(savedUser);
            bankRepo.save(account);
        });

        return toDto(savedUser);
    }

    @Caching(evict = {
            @CacheEvict(value = "banking:user:byId", key = "#id"),
            @CacheEvict(value = "banking:credit:score", allEntries = true),
            @CacheEvict(value = "banking:users:list", allEntries = true),
            @CacheEvict(value = "banking:accounts:list", allEntries = true),
            @CacheEvict(value = "banking:user:exists", allEntries = true),
            @CacheEvict(value = "banking:account:byNumber", allEntries = true)

    })
    @Transactional
    public void deleteUser(Long id) {

        bankRepo.findByUserId(id).ifPresent(account -> {
            bankRepo.delete(account);
        });
        buisnessLoggingService.log("USER DELETED", id.toString(),
                "USER DELETED WITH ID " + id);
        userRepo.deleteById(id);
    }

    public User ifUserExists(String username) {
        System.out.println("DB HIT -> ifUserExists: " + username);
        User user = userRepo.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("account not found"));
        return user;
    }

    @Transactional
    public void sendPasswordResetOtp(String email) {
        if (email == null || email.isBlank()) return;

        Optional<User> userOpt = userRepo.findByEmail(email);
        if (userOpt.isEmpty()) {
            return;
        }
        User user = userOpt.get();

        Instant now = Instant.now();
        Instant expiresAt = now.plus(OTP_TTL);
        String otp = generateOtp();

        PasswordResetToken token = new PasswordResetToken();
        token.setEmail(email);
        token.setOtp(otp);
        token.setExpiryTime(LocalDateTime.ofInstant(expiresAt, ZoneId.systemDefault()));
        resetTokenRepo.save(token);

        outboxPublisher.publish(
                subbyProperties.topics().notifications(),
                PasswordResetRequested.forUser(
                        String.valueOf(user.getId()),
                        email,
                        firstNonBlank(user.getFirstname(), user.getUsername()),
                        otp,
                        expiresAt));

        buisnessLoggingService.log(
                "PASSWORD_RESET_REQUESTED",
                user.getUsername(),
                "OTP issued for email " + email);
    }

    @Transactional
    public void resetPasswordWithOtp(String email, String otp, String newPassword) {
        if (email == null || otp == null || newPassword == null || newPassword.isBlank()) {
            throw new UnauthorizedException("email, otp and newPassword are required");
        }

        PasswordResetToken token = resetTokenRepo.findByEmailAndOtp(email, otp)
                .orElseThrow(() -> new UnauthorizedException("Invalid or expired OTP"));

        if (token.getExpiryTime() == null || token.getExpiryTime().isBefore(LocalDateTime.now())) {
            resetTokenRepo.delete(token);
            throw new UnauthorizedException("Invalid or expired OTP");
        }

        User user = userRepo.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepo.save(user);

        resetTokenRepo.delete(token);

        outboxPublisher.publish(
                subbyProperties.topics().notifications(),
                PasswordChanged.forUser(
                        String.valueOf(user.getId()),
                        email,
                        firstNonBlank(user.getFirstname(), user.getUsername()),
                        Instant.now()));

        buisnessLoggingService.log(
                "PASSWORD_RESET_COMPLETED",
                user.getUsername(),
                "Password updated via OTP for email " + email);
    }

    private static String generateOtp() {
        int n = OTP_RANDOM.nextInt(1_000_000);
        return String.format("%06d", n);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }

    public User authenticate(String username, String password) {
        Optional<User> userOpt = userRepo.findByUsername(username);
        if (userOpt.isEmpty()) return null;

        User user = userOpt.get();
        if (passwordEncoder.matches(password, user.getPassword())) {
            return user;
        }
        return null;
    }

    @Cacheable(value = "banking:credit:score", key = "#username", sync = true)
    public int fetchCreditScore(String username) {
        System.out.println("DB HIT CREDITSCORE: " + username);
        User user = userRepo.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("user not found"));
        int score = user.getCreditScore();
        return score;
    }

    private UserResponseDto toDto(User u) {
        return new UserResponseDto(
                u.getId(),
                u.getUsername(),
                u.getFirstname(),
                u.getLastname(),
                u.getEmail(),
                u.getMobile(),
                u.getRole(),
                u.getCreditScore(),
                u.getUpdatedAt(),
                u.isHasLoan(),
                u.getLoanamount(),
                u.getRemaining(),
                u.getDueDate()

        );
    }
}