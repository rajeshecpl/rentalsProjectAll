package com.rentopia.auth.dto;

import com.rentopia.auth.entity.OtpPurpose;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Set;
import java.util.UUID;

/** Request/response bodies for /api/v1/auth. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public enum AccountType { CUSTOMER, VENDOR }

    public record RegisterRequest(
            @NotBlank @Size(max = 150) String fullName,
            @NotBlank @Email @Size(max = 254) String email,
            @Pattern(regexp = "^\\+?[0-9]{10,15}$", message = "must be 10-15 digits, optionally starting with +")
            String phone,
            @NotBlank @Size(min = 8, max = 72, message = "must be 8-72 characters") String password,
            @NotNull AccountType accountType) {
    }

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record SendOtpRequest(@NotNull OtpPurpose purpose) {
    }

    public record VerifyOtpRequest(
            @NotNull OtpPurpose purpose,
            @NotBlank @Pattern(regexp = "^[0-9]{4,8}$", message = "must be numeric") String code) {
    }

    public record UserSummary(UUID id, String fullName, String email, String phone,
                              boolean emailVerified, boolean phoneVerified, Set<String> roles) {
    }

    public record AuthResponse(String tokenType, String accessToken, long expiresIn,
                               String refreshToken, UserSummary user) {
    }

    public record OtpSentResponse(String destinationHint, long expiresIn) {
    }
}
