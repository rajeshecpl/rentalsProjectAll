package com.rentopia.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "otp_codes")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OtpCode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private OtpChannel channel;

    @Column(nullable = false, length = 150)
    private String destination;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private OtpPurpose purpose;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(nullable = false)
    private short attempts;

    @Column(name = "max_attempts", nullable = false)
    private short maxAttempts;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public OtpCode(UUID userId, OtpChannel channel, String destination, OtpPurpose purpose,
                   String codeHash, short maxAttempts, Instant expiresAt) {
        this.userId = userId;
        this.channel = channel;
        this.destination = destination;
        this.purpose = purpose;
        this.codeHash = codeHash;
        this.maxAttempts = maxAttempts;
        this.expiresAt = expiresAt;
    }

    public boolean isUsable(Instant now) {
        return consumedAt == null && attempts < maxAttempts && expiresAt.isAfter(now);
    }
}
