package com.rentopia.auth.service;

import com.rentopia.auth.dto.AuthDtos.AuthResponse;
import com.rentopia.auth.entity.RefreshToken;
import com.rentopia.auth.repository.RefreshTokenRepository;
import com.rentopia.common.security.JwtProperties;
import com.rentopia.common.security.SecurityConfig;
import com.rentopia.common.util.Hashing;
import com.rentopia.user.entity.RoleCode;
import com.rentopia.user.entity.User;
import com.rentopia.user.service.UserMapper;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** Issues short-lived access JWTs and long-lived opaque refresh tokens. */
@Service
public class TokenService {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties props;
    private final RefreshTokenRepository refreshTokens;
    private final Clock clock;

    public TokenService(JwtEncoder jwtEncoder, JwtProperties props, RefreshTokenRepository refreshTokens, Clock clock) {
        this.jwtEncoder = jwtEncoder;
        this.props = props;
        this.refreshTokens = refreshTokens;
        this.clock = clock;
    }

    /** Must run inside the caller's transaction so the refresh token row commits with the login. */
    public IssuedTokens issue(User user, String userAgent, String ipAddress) {
        Instant now = clock.instant();
        List<String> roles = user.roleCodes().stream().map(RoleCode::name).sorted().toList();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(props.accessTokenTtl()))
                .claim(SecurityConfig.ROLES_CLAIM, roles)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String accessToken = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        String rawRefresh = Hashing.randomToken(48);
        RefreshToken saved = refreshTokens.save(new RefreshToken(
                user.getId(), Hashing.sha256(rawRefresh), now.plus(props.refreshTokenTtl()),
                truncate(userAgent, 300), truncate(ipAddress, 45)));
        return new IssuedTokens(accessToken, rawRefresh, saved);
    }

    public AuthResponse toResponse(User user, IssuedTokens tokens) {
        return new AuthResponse("Bearer", tokens.accessToken(), props.accessTokenTtl().toSeconds(),
                tokens.refreshToken(), UserMapper.toSummary(user));
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    public record IssuedTokens(String accessToken, String refreshToken, RefreshToken refreshEntity) {
    }
}
