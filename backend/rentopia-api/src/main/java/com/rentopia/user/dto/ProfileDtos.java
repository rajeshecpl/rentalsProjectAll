package com.rentopia.user.dto;

import com.rentopia.user.entity.Gender;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

public final class ProfileDtos {

    private ProfileDtos() {
    }

    public record ProfileResponse(UUID id, String fullName, String email, String phone,
                                  boolean emailVerified, boolean phoneVerified, Set<String> roles,
                                  LocalDate dateOfBirth, Gender gender, Instant createdAt) {
    }

    /** Full replacement of the editable fields (PUT semantics). Email changes get their own flow later. */
    public record UpdateProfileRequest(
            @NotBlank @Size(max = 150) String fullName,
            @Pattern(regexp = "^\\+?[0-9]{10,15}$", message = "must be 10-15 digits, optionally starting with +")
            String phone,
            @Past LocalDate dateOfBirth,
            Gender gender) {
    }
}
