package com.rentopia.auth.repository;

import com.rentopia.auth.entity.OtpCode;
import com.rentopia.auth.entity.OtpPurpose;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OtpCodeRepository extends JpaRepository<OtpCode, UUID> {

    long countByDestinationAndCreatedAtAfter(String destination, Instant after);

    Optional<OtpCode> findFirstByUserIdAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(UUID userId, OtpPurpose purpose);
}
