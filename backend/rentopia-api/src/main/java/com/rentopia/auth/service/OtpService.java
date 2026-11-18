package com.rentopia.auth.service;

import com.rentopia.auth.dto.AuthDtos.OtpSentResponse;
import com.rentopia.auth.entity.OtpChannel;
import com.rentopia.auth.entity.OtpCode;
import com.rentopia.auth.entity.OtpPurpose;
import com.rentopia.auth.repository.OtpCodeRepository;
import com.rentopia.common.exception.ApiException;
import com.rentopia.common.util.Hashing;
import com.rentopia.user.entity.User;
import com.rentopia.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Verification of the signed-in user's own phone number or email address. */
@Service
public class OtpService {

    private final OtpCodeRepository otpCodes;
    private final UserRepository users;
    private final OtpSender sender;
    private final OtpProperties props;
    private final Clock clock;

    public OtpService(OtpCodeRepository otpCodes, UserRepository users, OtpSender sender,
                      OtpProperties props, Clock clock) {
        this.otpCodes = otpCodes;
        this.users = users;
        this.sender = sender;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public OtpSentResponse send(UUID userId, OtpPurpose purpose) {
        User user = loadUser(userId);
        Target target = targetFor(user, purpose);
        Instant now = clock.instant();

        long recent = otpCodes.countByDestinationAndCreatedAtAfter(target.destination(), now.minus(props.sendWindow()));
        if (recent >= props.maxSendsPerWindow()) {
            throw ApiException.tooManyRequests("OTP_RATE_LIMITED", "Too many codes requested; try again later");
        }

        String code = Hashing.randomDigits(props.length());
        otpCodes.save(new OtpCode(userId, target.channel(), target.destination(), purpose,
                hash(userId, purpose, code), (short) props.maxAttempts(), now.plus(props.ttl())));
        sender.send(target.channel(), target.destination(), code);
        return new OtpSentResponse(mask(target.destination()), props.ttl().toSeconds());
    }

    /** Wrong codes count as attempts and must persist even though the request fails. */
    @Transactional(noRollbackFor = ApiException.class)
    public void verify(UUID userId, OtpPurpose purpose, String code) {
        User user = loadUser(userId);
        targetFor(user, purpose);
        Instant now = clock.instant();

        OtpCode otp = otpCodes.findFirstByUserIdAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(userId, purpose)
                .filter(o -> o.isUsable(now))
                .orElseThrow(() -> ApiException.badRequest("OTP_INVALID", "Code is invalid or expired"));

        otp.setAttempts((short) (otp.getAttempts() + 1));
        if (!Hashing.constantTimeEquals(otp.getCodeHash(), hash(userId, purpose, code))) {
            throw ApiException.badRequest("OTP_INVALID", "Code is invalid or expired");
        }
        otp.setConsumedAt(now);
        switch (purpose) {
            case VERIFY_PHONE -> user.setPhoneVerifiedAt(now);
            case VERIFY_EMAIL -> user.setEmailVerifiedAt(now);
            default -> throw new IllegalStateException("Unsupported purpose " + purpose);
        }
    }

    private User loadUser(UUID userId) {
        return users.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized("UNAUTHORIZED", "Authentication is required"));
    }

    private static Target targetFor(User user, OtpPurpose purpose) {
        return switch (purpose) {
            case VERIFY_PHONE -> {
                if (user.getPhone() == null) {
                    throw ApiException.badRequest("PHONE_MISSING", "Add a phone number to your profile first");
                }
                yield new Target(OtpChannel.SMS, user.getPhone());
            }
            case VERIFY_EMAIL -> new Target(OtpChannel.EMAIL, user.getEmail());
            default -> throw ApiException.badRequest("OTP_PURPOSE_UNSUPPORTED",
                    "This endpoint supports VERIFY_PHONE and VERIFY_EMAIL only");
        };
    }

    /** Bound to user and purpose so a code cannot be replayed for another purpose. */
    private static String hash(UUID userId, OtpPurpose purpose, String code) {
        return Hashing.sha256(userId + ":" + purpose + ":" + code);
    }

    private static String mask(String destination) {
        int at = destination.indexOf('@');
        if (at > 1) {
            return destination.charAt(0) + "***" + destination.substring(at);
        }
        return destination.length() <= 4 ? "****" : "******" + destination.substring(destination.length() - 4);
    }

    private record Target(OtpChannel channel, String destination) {
    }
}
