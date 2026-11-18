package com.rentopia.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** 1:1 with users (shared primary key). */
@Entity
@Table(name = "customer_profiles")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerProfile {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "text")
    private Gender gender;

    @Column(name = "avatar_s3_key", length = 500)
    private String avatarS3Key;

    @Column(name = "preferred_city_id")
    private UUID preferredCityId;

    @Version
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public CustomerProfile(UUID userId) {
        this.userId = userId;
    }
}
