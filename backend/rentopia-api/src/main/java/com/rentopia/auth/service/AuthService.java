package com.rentopia.auth.service;

import com.rentopia.auth.dto.AuthDtos.AccountType;
import com.rentopia.auth.dto.AuthDtos.AuthResponse;
import com.rentopia.auth.dto.AuthDtos.LoginRequest;
import com.rentopia.auth.dto.AuthDtos.RegisterRequest;
import com.rentopia.auth.entity.RefreshToken;
import com.rentopia.auth.repository.RefreshTokenRepository;
import com.rentopia.common.exception.ApiException;
import com.rentopia.common.util.Hashing;
import com.rentopia.user.entity.CustomerProfile;
import com.rentopia.user.entity.Role;
import com.rentopia.user.entity.RoleCode;
import com.rentopia.user.entity.User;
import com.rentopia.user.repository.CustomerProfileRepository;
import com.rentopia.user.repository.RoleRepository;
import com.rentopia.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users;
    private final RoleRepository roles;
    private final CustomerProfileRepository customerProfiles;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final Clock clock;
    /** Compared against when the email is unknown, so response time does not reveal which emails exist. */
    private final String dummyHash;

    public AuthService(UserRepository users, RoleRepository roles, CustomerProfileRepository customerProfiles,
                       RefreshTokenRepository refreshTokens, PasswordEncoder passwordEncoder,
                       TokenService tokenService, Clock clock) {
        this.users = users;
        this.roles = roles;
        this.customerProfiles = customerProfiles;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode(Hashing.randomToken(16));
    }

    @Transactional
    public AuthResponse register(RegisterRequest req, String userAgent, String ip) {
        String email = User.normalizeEmail(req.email());
        if (users.existsByEmail(email)) {
            throw ApiException.conflict("EMAIL_TAKEN", "An account with this email already exists");
        }
        if (req.phone() != null && users.existsByPhone(req.phone())) {
            throw ApiException.conflict("PHONE_TAKEN", "An account with this phone number already exists");
        }

        User user = new User(email, req.phone(), passwordEncoder.encode(req.password()), req.fullName().trim());
        // Every account can book as a customer; vendors additionally get VENDOR.
        // A vendor still needs an approved vendor profile before publishing (next milestone).
        user.getRoles().add(role(RoleCode.CUSTOMER));
        if (req.accountType() == AccountType.VENDOR) {
            user.getRoles().add(role(RoleCode.VENDOR));
        }
        user.setLastLoginAt(clock.instant());
        users.saveAndFlush(user);
        customerProfiles.save(new CustomerProfile(user.getId()));

        log.info("Registered user {} as {}", user.getId(), req.accountType());
        return tokenService.toResponse(user, tokenService.issue(user, userAgent, ip));
    }

    @Transactional
    public AuthResponse login(LoginRequest req, String userAgent, String ip) {
        User user = users.findByEmail(User.normalizeEmail(req.email())).orElse(null);
        if (user == null) {
            passwordEncoder.matches(req.password(), dummyHash);
            throw invalidCredentials();
        }
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw invalidCredentials();
        }
        if (!user.isActive()) {
            throw ApiException.forbidden("ACCOUNT_INACTIVE", "This account is not active");
        }
        user.setLastLoginAt(clock.instant());
        return tokenService.toResponse(user, tokenService.issue(user, userAgent, ip));
    }

    /**
     * Rotating refresh: the presented token is revoked and replaced. Presenting an
     * already-revoked token signals theft, so every session of that user is revoked.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public AuthResponse refresh(String rawToken, String userAgent, String ip) {
        Instant now = clock.instant();
        RefreshToken current = refreshTokens.findByTokenHashForUpdate(Hashing.sha256(rawToken))
                .orElseThrow(AuthService::invalidRefresh);

        if (current.getRevokedAt() != null) {
            int revoked = refreshTokens.revokeAllForUser(current.getUserId(), now);
            log.warn("Refresh token reuse detected for user {}; revoked {} active sessions",
                    current.getUserId(), revoked);
            throw ApiException.unauthorized("REFRESH_TOKEN_REUSED", "Session is no longer valid; please log in again");
        }
        if (!current.isUsable(now)) {
            throw invalidRefresh();
        }

        User user = users.findById(current.getUserId()).orElseThrow(AuthService::invalidRefresh);
        if (!user.isActive()) {
            throw ApiException.forbidden("ACCOUNT_INACTIVE", "This account is not active");
        }

        TokenService.IssuedTokens issued = tokenService.issue(user, userAgent, ip);
        current.setRevokedAt(now);
        current.setReplacedBy(issued.refreshEntity().getId());
        return tokenService.toResponse(user, issued);
    }

    /** Idempotent: unknown or already-revoked tokens are ignored. */
    @Transactional
    public void logout(String rawToken) {
        refreshTokens.findByTokenHashForUpdate(Hashing.sha256(rawToken))
                .filter(t -> t.getRevokedAt() == null)
                .ifPresent(t -> t.setRevokedAt(clock.instant()));
    }

    private Role role(RoleCode code) {
        return roles.findByCode(code.name())
                .orElseThrow(() -> new IllegalStateException("Role not seeded: " + code));
    }

    private static ApiException invalidCredentials() {
        return ApiException.unauthorized("INVALID_CREDENTIALS", "Email or password is incorrect");
    }

    private static ApiException invalidRefresh() {
        return ApiException.unauthorized("INVALID_REFRESH_TOKEN", "Refresh token is invalid or expired");
    }
}
